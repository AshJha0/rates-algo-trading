package io.ratesalgo.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.CompetitorModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.strategy.ModelSpreadStrategy;
import io.ratesalgo.hedge.AxeSource;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.quoting.ClientTier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests of the feature components, each against its stated formula. */
class FeaturesTest {

  private static final long MM = InstrumentSet.MILLION;
  private static final InstrumentSet USD = InstrumentSet.usdDefault();

  private static RiskLadder ladderWith(final int id, final long position) {
    final RiskLadder l = Support.ladder();
    l.setPosition(l.indexOf(id), position);
    l.recompute();
    return l;
  }

  @Test
  @DisplayName("ladder: net and buckets net swaps against bonds and futures in one tenor")
  void ladderNetsAcrossProducts() {
    final RiskLadder l = Support.ladder();
    l.setPosition(l.indexOf(InstrumentSet.SOFR_10Y), 100 * MM); // payer: DV01 +81,100
    l.setPosition(l.indexOf(InstrumentSet.UST_10Y), 100 * MM); // long bond: DV01 -81,500
    l.recompute();
    assertEquals(81_100 - 81_500, l.net(), 1e-6);
    assertEquals(l.net(), l.bucket(2), 1e-6);
    assertEquals(0.0, l.bucket(0), 0.0);
    assertEquals(400.0, l.ladderNorm(), 1e-6);
    assertEquals(-1, l.indexOf(999));
    assertEquals("30Y", RiskLadder.label(RiskLadder.bucketOf(30)));
    assertEquals(1, RiskLadder.bucketOf(5));
    assertEquals(-l.tradeDv01(0, true, MM), l.tradeDv01(0, false, MM), 0.0);
    assertThrows(IllegalArgumentException.class, () -> new RiskLadder(List.of()));
    final Instrument i = USD.get(InstrumentSet.SOFR_2Y);
    assertThrows(IllegalArgumentException.class, () -> new RiskLadder(List.of(i, i)));
  }

  @Test
  @DisplayName("hedge budget: threshold hedges everything, netting holds then hedges the residual")
  void hedgeBudgets() {
    final RiskLadder l = ladderWith(InstrumentSet.SOFR_10Y, 250 * MM); // +202,750 DV01
    final HedgeBudget threshold = HedgeBudget.threshold(100_000);
    assertEquals(l.net(), threshold.excessDv01(l, 0), 0.0);
    assertEquals(0L, threshold.holdNanos());
    assertEquals(0.0, threshold.excessDv01(ladderWith(InstrumentSet.SOFR_10Y, 100 * MM), 0), 0.0);
    final NettingHedgeBudget netting = new NettingHedgeBudget(150_000, 400_000, 50_000, 120_000_000_000L);
    assertEquals(0.0, netting.excessDv01(l, 1_000_000_000L), 0.0); // still holding
    assertEquals(l.net() - 50_000, netting.excessDv01(l, 120_000_000_000L), 1e-9);
    final RiskLadder big = ladderWith(InstrumentSet.SOFR_10Y, -600 * MM); // -486,600
    assertEquals(big.net() + 150_000, netting.excessDv01(big, 0), 1e-9);
    assertEquals(120_000_000_000L, netting.holdNanos());
    assertEquals(150_000, netting.softDv01(), 0.0);
    assertEquals(400_000, netting.hardDv01(), 0.0);
    assertThrows(IllegalArgumentException.class, () -> new NettingHedgeBudget(10, 5, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ThresholdHedgeBudget(0));
  }

  @Test
  @DisplayName("hedge timing: waits while opposing flow is expected, stages when impact dominates")
  void hedgeTiming() {
    final FlowRateEstimator flow = new FlowRateEstimator(900, 50);
    final OptimalStoppingTiming t =
        new OptimalStoppingTiming(flow, 1e-6, 0.045, 0.13, 0.0, 30_000_000_000L, 60_000_000_000L, 1, 0);
    // small exposure: saving 0.13 * min(20k, 50 * 30) = 195 beats riskCost 0.5e-6 * 0.045^2 * 4e8 * 30 = 12
    assertEquals(HedgeTiming.Action.WAIT, t.decide(20_000, 0));
    assertEquals(HedgeTiming.Action.WAIT, t.decide(20_000, 30_000_000_000L));
    // the maximum wait is reached
    assertEquals(HedgeTiming.Action.IMMEDIATE, t.decide(20_000, 60_000_000_000L));
    t.reset();
    // large exposure: risk dominates
    assertEquals(HedgeTiming.Action.IMMEDIATE, t.decide(2_000_000, 0));
    assertEquals(HedgeTiming.Action.IMMEDIATE, t.decide(0.0, 0));
    final OptimalStoppingTiming staged =
        new OptimalStoppingTiming(
            new FlowRateEstimator(900, 0), 1e-6, 0.045, 0.13, 6.1e-7, 30_000_000_000L, 0, 3, 20_000_000_000L);
    assertTrue(staged.stagingGain(100_000) > 0);
    assertEquals(HedgeTiming.Action.STAGED, staged.decide(100_000, 0));
    assertEquals(3, staged.slices());
    assertEquals(20_000_000_000L, staged.sliceIntervalNanos());
    assertEquals(30_000_000_000L, staged.waitNanos());
    assertEquals(1e-6, staged.gamma(), 0.0);
    assertEquals(0.045, staged.sigmaBpPerSqrtSecond(), 0.0);
    assertEquals(HedgeTiming.Action.IMMEDIATE, HedgeTiming.ALWAYS_IMMEDIATE.decide(1e9, 0));
    assertEquals(1, HedgeTiming.ALWAYS_IMMEDIATE.slices());
    assertEquals(0L, HedgeTiming.ALWAYS_IMMEDIATE.waitNanos());
    assertEquals(0L, HedgeTiming.ALWAYS_IMMEDIATE.sliceIntervalNanos());
    assertThrows(
        IllegalArgumentException.class,
        () -> new OptimalStoppingTiming(flow, 0, 0.045, 0.13, 0, 1, 0, 1, 0));
  }

  @Test
  @DisplayName("flow estimate: decays exponentially and adds |dv01| / tau per fill on its side")
  void flowEstimate() {
    final FlowRateEstimator f = new FlowRateEstimator(100, 10);
    f.onFill(-1_000, 0);
    assertEquals(20.0, f.opposingRate(5_000, 0), 1e-9); // opposing a long: the down side
    assertEquals(10.0, f.opposingRate(-5_000, 0), 1e-9);
    assertEquals(20.0 * Math.exp(-1.0), f.opposingRate(5_000, 100_000_000_000L), 1e-9);
    f.onFill(2_000, 100_000_000_000L);
    assertEquals(10.0 * Math.exp(-1.0) + 20.0, f.opposingRate(-1, 100_000_000_000L), 1e-9);
    assertThrows(IllegalArgumentException.class, () -> new FlowRateEstimator(0, 1));
  }

  @Test
  @DisplayName("axes: improve the side that reduces the exposure, scaled by exposure over budget")
  void axes() {
    final RiskLadder l = ladderWith(InstrumentSet.SOFR_10Y, 100 * MM); // +81,100 DV01
    final AxeBook axes = new AxeBook(l, 0.10, 162_200, 0.5);
    // selling the 10Y payer swap reduces the long; buying it adds
    assertEquals(0.05, axes.axeBp(InstrumentSet.SOFR_10Y, Side.Sell), 1e-12);
    assertEquals(0.0, axes.axeBp(InstrumentSet.SOFR_10Y, Side.Buy), 0.0);
    // buying the 10Y note also reduces it (long bond has negative DV01)
    assertTrue(axes.axeBp(InstrumentSet.UST_10Y, Side.Buy) > 0);
    // the 2Y swap: bucket 2Y is flat, so only half the net counts
    assertEquals(0.10 * 0.5 * 81_100 / 162_200, axes.axeBp(InstrumentSet.SOFR_2Y, Side.Sell), 1e-12);
    assertEquals(100.0 * MM, (double) axes.axeQuantity(InstrumentSet.SOFR_10Y, Side.Sell), 1.0);
    assertEquals(0L, axes.axeQuantity(InstrumentSet.SOFR_10Y, Side.Buy));
    assertEquals(0.0, axes.axeBp(999, Side.Buy), 0.0);
    final List<AxeBook.Axe> list = axes.publish();
    assertFalse(list.isEmpty());
    for (final AxeBook.Axe a : list) {
      assertTrue(a.improveBp() > 0 && a.quantity() > 0, a.toString());
    }
    assertEquals(0.0, AxeSource.NONE.axeBp(InstrumentSet.SOFR_10Y, Side.Sell), 0.0);
    assertThrows(IllegalArgumentException.class, () -> new AxeBook(l, 0.1, 0, 0.5));
  }

  @Test
  @DisplayName("sizing: full size when risk-reducing, decline beyond the hard envelope, premium in between")
  void sizing() {
    final RiskLadder l = ladderWith(InstrumentSet.SOFR_10Y, 300 * MM); // +243,300
    final int i = l.indexOf(InstrumentSet.SOFR_10Y);
    final EnvelopeSizer s = new EnvelopeSizer(1_000_000, 0.1, 0.5, 0.2, 100, 0.05);
    assertEquals(50 * MM, s.maxQuantity(l, i, l.tradeDv01(i, false, 50 * MM), 50 * MM));
    assertEquals(0.0, s.premiumBp(l, i, l.tradeDv01(i, false, 50 * MM), 50), 0.0);
    assertEquals(0L, s.maxQuantity(l, i, l.tradeDv01(i, true, 400 * MM), 400 * MM));
    final double add = l.tradeDv01(i, true, 50 * MM); // post 283,850, u = 0.28385
    final double x = (0.28385 - 0.1) / 0.4;
    assertEquals(0.2 * x * x, s.premiumBp(l, i, add, 50), 1e-9);
    assertEquals(50 * MM, s.maxQuantity(l, i, add, 50 * MM));
    assertEquals(0.05 * Math.log(2), s.premiumBp(l, i, -1, 200), 1e-12);
    assertEquals(Math.exp(1 + 2 * 0.5), EnvelopeSizer.lognormalQuantileMm(1, 0.5, 2), 1e-12);
    assertEquals(7L, QuoteSizer.UNLIMITED.maxQuantity(l, i, 1e9, 7L));
    assertEquals(0.0, QuoteSizer.UNLIMITED.premiumBp(l, i, 1e9, 1e3), 0.0);
    assertThrows(IllegalArgumentException.class, () -> new EnvelopeSizer(1, 0.5, 0.5, 0, 1, 0));
  }

  @Test
  @DisplayName("funding: adding negative-carry bonds costs, reducing inventory earns, swaps pay balance sheet only")
  void funding() {
    final RiskLadder l = Support.ladder();
    final double[] coupons = new double[l.size()];
    coupons[l.indexOf(InstrumentSet.UST_10Y)] = 0.0395;
    final FundingPenalty p = new FundingPenalty(l, coupons, 0.005, 0.0005, 5);
    final int b = l.indexOf(InstrumentSet.UST_10Y);
    // buy 50mm 10Y from flat: (0.0425 - 0.0395 + 0.005) * 50mm * 5 / 360, per 40,750 DV01
    final double expected = (0.003 + 0.005) * 50e6 * 5 / 360 / 40_750;
    assertEquals(expected, p.penaltyBp(l, b, true, 50 * MM), 1e-9);
    // selling from flat: earns the negative carry back but pays balance sheet
    assertEquals((-0.003 + 0.005) * 50e6 * 5 / 360 / 40_750, p.penaltyBp(l, b, false, 50 * MM), 1e-9);
    l.setPosition(b, 50 * MM);
    l.recompute();
    assertTrue(p.penaltyBp(l, b, false, 50 * MM) < 0, "reducing a long is credited");
    final int s = l.indexOf(InstrumentSet.SOFR_10Y);
    assertEquals(0.0005 * 50e6 * 5 / 360 / 40_550, p.penaltyBp(l, s, true, 50 * MM), 1e-9);
    assertEquals(0.0, p.penaltyBp(l, l.indexOf(InstrumentSet.TY_FUTURE), true, MM), 0.0);
    assertEquals(0.0, InventoryPenalty.NONE.penaltyBp(l, b, true, MM), 0.0);
    assertThrows(IllegalArgumentException.class, () -> new FundingPenalty(l, new double[1], 0, 0, 1));
  }

  @Test
  @DisplayName("carry: bonds accrue their coupon on face, Act/360; other kinds accrue nothing")
  void couponCarry() {
    final CouponCarry c = new CouponCarry(Map.of(InstrumentSet.UST_10Y, 0.0395));
    final long day = 86_400_000_000_000L;
    assertEquals(
        100 * MM * 1e-2 * 0.0395 / 360,
        c.carry(USD.get(InstrumentSet.UST_10Y), 100 * MM, 100.0, 0, day),
        1e-9);
    assertEquals(0.0, c.carry(USD.get(InstrumentSet.UST_2Y), 100 * MM, 100.0, 0, day), 0.0);
    assertEquals(0.0, c.carry(USD.get(InstrumentSet.SOFR_10Y), 100 * MM, 4.0, 0, day), 0.0);
    assertEquals(0.0, c.coupon(InstrumentSet.UST_2Y), 0.0);
  }

  @Test
  @DisplayName("tiering: minimum sample, demotion and promotion with hysteresis, four-eyes publication")
  void tiering() {
    final List<ClientTier> tiers =
        List.of(
            new ClientTier(1, 1.0, Long.MAX_VALUE, true, false),
            new ClientTier(2, 1.0, Long.MAX_VALUE, true, false),
            new ClientTier(3, 1.0, Long.MAX_VALUE, true, false));
    final MarkoutTiering t =
        new MarkoutTiering(
            tiers, new double[] {-0.045, -0.098, -1e9}, new double[] {1e9, -0.035, -0.088}, 3, 1.0, 1, 0, 9001, 9002,
            16);
    assertTrue(t.wantsMarkouts());
    assertEquals(2, t.tier(7));
    t.onMarkout(7, -0.5, 0);
    t.onMarkout(7, -0.5, 0);
    t.review(0);
    assertEquals(2, t.tier(7), "below the minimum sample nothing moves");
    t.onMarkout(7, -0.5, 0);
    t.review(0);
    assertEquals(3, t.tier(7));
    assertEquals(2L, t.control().active().version());
    // inside the hysteresis band: stays in tier 3
    t.onMarkout(7, -0.09, 1);
    t.review(1);
    assertEquals(3, t.tier(7));
    t.onMarkout(7, 0.0, 2);
    t.review(2);
    assertEquals(2, t.tier(7));
    t.onMarkout(7, 0.0, 3);
    t.review(3);
    assertEquals(1, t.tier(7));
    assertEquals(3L, t.versionsPublished());
    assertEquals(4L, t.control().active().version());
    assertEquals(0.0, t.meanMarkoutBp(7), 0.0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new MarkoutTiering(tiers, new double[] {-0.1, -0.2, 0}, new double[] {0, -0.15, -0.3}, 1, 1, 0, 0, 1, 2, 4));
    final StaticTiering s = new StaticTiering(4);
    assertEquals(1, s.tier(4));
    assertEquals(2, s.tier(5));
    assertFalse(s.wantsMarkouts());
    s.onMarkout(1, 1, 1);
    s.review(1);
    assertThrows(IllegalArgumentException.class, () -> new StaticTiering(0));
  }

  @Test
  @DisplayName("tiering review: a new version waits for the review interval")
  void tieringReviewInterval() {
    final List<ClientTier> tiers =
        List.of(new ClientTier(1, 1.0, 1, true, false), new ClientTier(2, 1.0, 1, true, false));
    final MarkoutTiering t =
        new MarkoutTiering(tiers, new double[] {-0.1, -1e9}, new double[] {1e9, -0.05}, 1, 1.0, 0, 100, 1, 2, 4);
    t.onMarkout(1, -1, 0);
    t.review(0);
    assertEquals(2, t.tier(1));
    t.onMarkout(1, 0, 10);
    t.review(10);
    assertEquals(2, t.tier(1), "inside the review interval");
    t.review(100);
    assertEquals(1, t.tier(1));
  }

  @Test
  @DisplayName("spread optimiser: identical to ModelSpreadStrategy with no extra cost, wider with one")
  void spreadOptimizerMatchesModelSpread() {
    final Presets presets = Support.presets(BandPoint.CENTRAL);
    final HitRateModel hit = HitRateModel.fromPreset(presets.get(HitRateModel.PRESET), BandPoint.CENTRAL);
    final CompetitorModel comp = CompetitorModel.fromPreset(presets.get(CompetitorModel.PRESET));
    final double[] adverse = {0.0241, 0.0667, 0.13};
    final SpreadOptimizer o = new SpreadOptimizer(hit, comp, adverse, 0.2459, 0.2459, 2.0, 0.01);
    final ModelSpreadStrategy m = new ModelSpreadStrategy(hit, comp, adverse, 0.2459, 0.2459, 2.0, 0.01, 1, 4);
    for (final Instrument i : USD.all()) {
      for (final double size : new double[] {1, 25, 400}) {
        for (int tier = 0; tier <= 4; tier++) {
          for (int dealers = 2; dealers <= 8; dealers += 3) {
            assertEquals(
                m.optimalHalfSpreadBp(i, size, tier, dealers, tier % 2 == 0),
                o.optimalHalfSpreadBp(i, size, tier, dealers, tier % 2 == 0, 0.0),
                0.0);
          }
        }
      }
    }
    final Instrument ten = USD.get(InstrumentSet.SOFR_10Y);
    assertTrue(o.optimalHalfSpreadBp(ten, 25, 1, 3, false, 0.3) > o.optimalHalfSpreadBp(ten, 25, 1, 3, false, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new SpreadOptimizer(hit, comp, adverse, 0, 0, 1, 0));
  }
}
