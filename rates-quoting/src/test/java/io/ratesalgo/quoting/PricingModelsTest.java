package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** P&L audit items 2 (slope learning), 5 (no blocking by default) and 11 (cover averaging). */
final class PricingModelsTest {

  private static final double BASE = 0.0;

  /**
   * Feeds {@code n} seeded outcomes drawn from a logistic with intercept {@code b0} and slope
   * {@code b1}, at spreads uniform on [0, maxSpread], to a model at the shipped default.
   */
  private static HitRateModel learn(
      final double b0, final double b1, final double maxSpread, final int n, final long seed)
      throws IOException {
    final HitRateModel model = new HitRateModel(HitRatePreset.shippedDefault(), 0.01);
    final SplittableRandom rnd = new SplittableRandom(seed);
    for (int i = 0; i < n; i++) {
      final double s = rnd.nextDouble() * maxSpread;
      final boolean won = rnd.nextDouble() < HitRateModel.logistic(b0 + BASE + b1 * s);
      model.update(BASE, s, won);
    }
    return model;
  }

  @Test
  void learnsASteepSlopeBeyondTheDeclaredBand() throws IOException {
    for (final long seed : new long[] {11L, 12L, 13L}) {
      final HitRateModel m = learn(0.5, -5.0, 1.2, 40_000, seed);
      assertEquals(-5.0, m.learnedSlope(), 0.6, "seed " + seed);
      assertEquals(0.5, m.learnedIntercept(), 0.4, "seed " + seed);
      assertTrue(m.slopeOutsideDeclaredBand());
      assertTrue(m.learnedSlope() < m.declaredSlopeLow());
      assertTrue(m.slopeStandardError() > 0.0 && m.slopeStandardError() < 0.6);
      assertEquals(40_000, m.updates());
    }
  }

  @Test
  void learnsAShallowSlopeAboveTheDeclaredBand() throws IOException {
    final HitRateModel m = learn(-0.5, -0.5, 3.0, 40_000, 21L);
    assertEquals(-0.5, m.learnedSlope(), 0.2);
    assertTrue(m.learnedSlope() > m.declaredSlopeHigh());
    assertTrue(m.slopeOutsideDeclaredBand());
  }

  @Test
  void staysInsideTheBandWhenTheMarketMatchesTheAssumption() throws IOException {
    final HitRateModel m = learn(1.0, -1.6, 2.0, 40_000, 31L);
    assertEquals(-1.6, m.learnedSlope(), 0.3);
    assertFalse(m.slopeOutsideDeclaredBand());
  }

  @Test
  void firstStepEqualsGradientAscentAndZeroRateLearnsNothing() throws IOException {
    final HitRatePreset p = HitRatePreset.shippedDefault();
    final HitRateModel m = new HitRateModel(p, 0.01);
    final double pWin = m.winProbability(BASE, 0.5);
    m.update(BASE, 0.5, true);
    // prior precision 1 / rate dominates the first step: close to rate * err * x
    assertEquals(1.0 + 0.01 * (1.0 - pWin), m.learnedIntercept(), 1e-3);
    assertEquals(-1.6 + 0.01 * (1.0 - pWin) * 0.5, m.learnedSlope(), 1e-3);
    final HitRateModel frozen = new HitRateModel(p, 0.0);
    frozen.update(BASE, 0.5, true);
    assertEquals(-1.6, frozen.learnedSlope(), 0.0);
    assertEquals(1, frozen.updates());
    assertEquals(1, frozen.wins());
    assertTrue(Double.isNaN(frozen.slopeStandardError()));
  }

  @Test
  void learningBoundsAreClampedAndValidated() throws IOException {
    final HitRatePreset p = HitRatePreset.shippedDefault();
    final HitRateModel m = new HitRateModel(p, 0.5);
    for (int i = 0; i < 5_000; i++) {
      m.update(BASE, 1.0, false);
    }
    assertEquals(HitRateModel.INTERCEPT_LEARN_LOW, m.learnLow(HitRateModel.INTERCEPT), 0.0);
    assertTrue(m.learnedSlope() >= HitRateModel.SLOPE_LEARN_LOW);
    assertTrue(m.learnedIntercept() >= HitRateModel.INTERCEPT_LEARN_LOW);
    assertThrows(IllegalArgumentException.class, () -> new HitRateModel(p, 0.01, -2.0, -1.0));
    assertThrows(IllegalArgumentException.class, () -> new HitRateModel(p, 0.01, -8.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new HitRateModel(p, -1.0));
    assertEquals(-2.4, m.declaredSlopeLow(), 0.0);
    assertEquals(-1.0, m.declaredSlopeHigh(), 0.0);
  }

  @Test
  void zeroCoverSpreadIsThePointEstimate() throws IOException {
    final HitRateModel h = new HitRateModel(HitRatePreset.shippedDefault(), 0.0);
    final SpreadModel s = new SpreadModel(h);
    assertEquals(0.0, s.coverSigmaBp(), 0.0);
    for (double x = 0.0; x <= 3.0; x += 0.25) {
      assertEquals(h.winProbability(BASE, x), s.winProbability(BASE, x), 0.0);
    }
    s.observeCoverGap(0.3);
    assertEquals(0.0, s.coverSigmaBp(), 0.0, "one gap gives no spread yet");
    s.observeCoverGap(Double.NaN);
    assertEquals(1, s.coverGaps());
    assertThrows(IllegalArgumentException.class, () -> new SpreadModel(h, -0.1));
  }

  @Test
  void averagesWinProbabilityOverTheObservedCoverSpread() throws IOException {
    final HitRateModel h = new HitRateModel(HitRatePreset.shippedDefault(), 0.0);
    final SpreadModel s = new SpreadModel(h);
    final SplittableRandom rnd = new SplittableRandom(41L);
    for (int i = 0; i < 20_000; i++) {
      // Box-Muller: gaps with mean 0.3 bp and spread 0.4 bp
      final double z =
          Math.sqrt(-2.0 * Math.log(1.0 - rnd.nextDouble())) * Math.cos(2.0 * Math.PI * rnd.nextDouble());
      s.observeCoverGap(0.3 + 0.4 * z);
    }
    assertEquals(0.4, s.coverSigmaBp(), 0.05);
    assertEquals(0.3, s.meanCoverGapBp(), 0.05);

    // brute-force average over the same normal agrees with the quadrature
    final double sigma = s.coverSigmaBp();
    for (final double x : new double[] {0.0, 0.5, 1.0, 2.0}) {
      double sum = 0.0;
      double wsum = 0.0;
      for (double g = -6.0; g <= 6.0; g += 0.001) {
        final double w = Math.exp(-0.5 * g * g);
        sum += w * h.winProbability(BASE, x - sigma * g);
        wsum += w;
      }
      assertEquals(sum / wsum, s.winProbability(BASE, x), 1e-6, "at " + x);
    }

    // the averaged probability is flatter: higher in the convex tail, lower near certainty
    assertTrue(s.winProbability(BASE, 3.0) > h.winProbability(BASE, 3.0));
    assertTrue(s.winProbability(BASE, -2.0) < h.winProbability(BASE, -2.0));

    // the optimiser maximises the averaged objective, not the point one
    final SpreadModel point = new SpreadModel(h);
    final double c = 0.3;
    final double optAvg = s.optimal(BASE, c, 10.0);
    final double optPoint = point.optimal(BASE, c, 10.0);
    assertNotEquals(optPoint, optAvg, 1e-4);
    assertTrue(s.expected(BASE, c, optAvg) >= s.expected(BASE, c, optPoint));
    assertTrue(s.expected(BASE, c, optAvg) >= s.expected(BASE, c, optAvg + 0.05));
    assertTrue(s.expected(BASE, c, optAvg) >= s.expected(BASE, c, optAvg - 0.05));
  }

  @Test
  void priorCoverSpreadAppliesUntilGapsArrive() throws IOException {
    final HitRateModel h = new HitRateModel(HitRatePreset.shippedDefault(), 0.0);
    final SpreadModel s = new SpreadModel(h, 0.25);
    assertEquals(0.25, s.coverSigmaBp(), 0.0);
    assertTrue(s.winProbability(BASE, 3.0) > h.winProbability(BASE, 3.0));
    s.observeCoverGap(0.1);
    s.observeCoverGap(0.1);
    assertEquals(0.0, s.coverSigmaBp(), 1e-12, "observed gaps replace the prior");
  }

  @Test
  void defaultTiersBlockNothingAndWidenInstead() {
    final TierTable t = TierTable.standard(1, 100 * FACE_1M, Map.of(7, 0, 8, 2));
    assertEquals(0, t.blockedTiers());
    assertEquals(1, t.tierOf(7).tier());
    assertEquals(2, t.tierOf(99).tier(), "unmapped clients get tier 2");
    assertEquals(3, t.tierOf(8).tier());
    assertTrue(t.tierOf(8).spreadMultiplier() > t.tierOf(99).spreadMultiplier());
    assertTrue(t.tierOf(99).spreadMultiplier() > t.tierOf(7).spreadMultiplier());
    assertFalse(t.tierOf(8).blocked());
    assertEquals(1.35, TierTable.standardMultiplier(3), 0.0);
    assertEquals(0, TierTable.single(1, new ClientTier(1, 1.0, FACE_1M, true, false)).blockedTiers());
  }

  @Test
  void unblockedTurnsABlockIntoAWiderQuote() {
    final TierTable blocked = TestMarket.tierTable(1);
    assertEquals(1, blocked.blockedTiers());
    final TierTable open = blocked.unblocked(2, TierTable.DEFAULT_BLOCK_WIDENING);
    assertEquals(0, open.blockedTiers());
    assertEquals(2, open.version());
    assertEquals(4, open.tierOf(4).tier());
    assertEquals(1.5, open.tierOf(4).spreadMultiplier(), 1e-12);
    assertEquals(0.8, open.tierOf(1).spreadMultiplier(), 0.0, "other tiers unchanged");
    assertEquals(2, open.tierOf(12345).tier(), "default tier kept");
    assertThrows(IllegalArgumentException.class, () -> blocked.unblocked(2, 0.9));
  }
}
