package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.CompetitorModel;
import io.ratesalgo.backtest.model.HedgeCostModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.model.RfqOutcomeModel;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.pnl.CarryModel;
import io.ratesalgo.backtest.pnl.PositionBook;
import io.ratesalgo.backtest.util.Prices;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Audit fix package D (PNL_AUDIT.md): depth-walk hedge fills, best-price client, coupon carry. */
class FixPackageDTest {

  private static final Presets PRESETS = TestSupport.presets();
  private static final Instrument TY = InstrumentSet.usdDefault().get(InstrumentSet.TY_FUTURE);
  private static final Instrument TEN = InstrumentSet.usdDefault().get(InstrumentSet.SOFR_10Y);
  private static final long DAY = 86_400_000_000_000L;

  @TempDir Path dir;

  // ---------------------------------------------------------------- hedge depth walk

  @Test
  void aHedgeInsideTheTouchPaysOnlyTheTouch() {
    final HedgeCostModel preset = HedgeCostModel.fromPreset(PRESETS.get(HedgeCostModel.PRESET));
    assertFalse(preset.depthWalk(), "the preset keeps the rule the ledger rows were recorded with");
    final HedgeCostModel h = preset.withDepthWalk(true);
    final long tick = TY.tickMantissa();
    final long mid = Prices.toMantissa(112.0) + tick / 2; // mid on a half tick, as on a one-tick book
    final long touchQty = 500 * TY.lotSize();
    final HedgeCostModel.Fill buy = h.fill(TY, true, 100 * TY.lotSize(), mid, tick / 2, touchQty);
    final HedgeCostModel.Fill sell = h.fill(TY, false, 100 * TY.lotSize(), mid, tick / 2, touchQty);
    assertEquals(mid + tick / 2, buy.priceMantissa());
    assertEquals(mid - tick / 2, sell.priceMantissa());
    assertEquals(150.0, buy.fee(), 1e-9);
    // the rule before package D charged one full tick more for the same order
    final HedgeCostModel.Fill linear = h.withDepthWalk(false).fill(TY, true, 100 * TY.lotSize(), mid, tick / 2, touchQty);
    assertEquals(buy.priceMantissa() + tick, linear.priceMantissa());
  }

  @Test
  void aBlockWalksTheLevelsAndAveragesThePrice() {
    final HedgeCostModel h = new HedgeCostModel(0.0002, 1.5, 2.0, true);
    final long tick = TY.tickMantissa();
    final long mid = Prices.toMantissa(112.0) + tick / 2;
    final long touch = mid + tick / 2;
    final long q = 500 * TY.lotSize();
    // 1,000 lots: 500 at the touch, 500 one tick beyond, average half a tick beyond
    assertEquals(touch + tick / 2, h.fill(TY, true, 2 * q, mid, tick / 2, q).priceMantissa());
    // 1,500 lots: one level each at 0, 1 and 2 ticks: one tick beyond on average
    assertEquals(touch + tick, h.fill(TY, true, 3 * q, mid, tick / 2, q).priceMantissa());
    // 2,500 lots: the last 1,500 all at the cap of 2 ticks: (0 + 1 + 3 * 2) / 5 = 1.4 ticks
    assertEquals(touch + (long) Math.ceil(1.4 * tick), h.fill(TY, true, 5 * q, mid, tick / 2, q).priceMantissa());
    // selling mirrors buying
    assertEquals(mid - tick / 2 - tick, h.fill(TY, false, 3 * q, mid, tick / 2, q).priceMantissa());
    // the cost never exceeds the cap beyond the touch, whatever the size
    assertTrue(h.fill(TY, true, 1_000_000 * TY.lotSize(), mid, tick / 2, q).priceMantissa() <= touch + 2 * tick);
  }

  @Test
  void withoutDisplayedDepthTheLinearRuleApplies() {
    final HedgeCostModel h = new HedgeCostModel(0.001, 1.5, 1.0, true);
    final long mid = Prices.toMantissa(112.0);
    final long half = TY.tickMantissa() / 2;
    assertEquals(
        h.fill(TY, true, 100 * TY.lotSize(), mid, half).priceMantissa(),
        h.fill(TY, true, 100 * TY.lotSize(), mid, half, 0L).priceMantissa());
    assertFalse(new HedgeCostModel(0.001, 1.5, 1.0).depthWalk());
    assertThrows(IllegalArgumentException.class, () -> h.fill(TY, true, 0, mid, half, 10L));
  }

  @Test
  void theRunnerFillsHedgesAgainstDisplayedDepth() {
    final Path journal = TestSupport.day(dir, LocalDate.of(2026, 1, 6));
    final BacktestConfig linear = TestSupport.config(journal, BandPoint.CENTRAL);
    final BacktestConfig depth = with(linear, linear.outcomes(), linear.hedgeCost().withDepthWalk(true), linear.carry());
    final BacktestResult a = new BacktestRunner(depth).run(TestSupport.fixed());
    final BacktestResult b = new BacktestRunner(linear).run(TestSupport.fixed());
    assertTrue(a.hedgeFills() > 0, "the day hedges");
    assertEquals(b.hedgeFills(), a.hedgeFills(), "the hedge decisions do not depend on the fill rule");
    assertEquals(b.total().spreadCapture(), a.total().spreadCapture(), 1e-6);
    assertTrue(a.total().hedgeCost() > b.total().hedgeCost(),
        "depth " + a.total().hedgeCost() + " vs linear " + b.total().hedgeCost());
    assertTrue(Math.abs(a.total().residual()) < 0.01);
  }

  // ---------------------------------------------------------------- best-price client

  @Test
  void theBestPriceClientTradesOnlyInsideTheCover() {
    final var hit = PRESETS.get(HitRateModel.PRESET);
    final CompetitorModel comp = CompetitorModel.fromPreset(PRESETS.get(CompetitorModel.PRESET));
    final RfqOutcomeModel soft = new RfqOutcomeModel(HitRateModel.fromPreset(hit, BandPoint.CENTRAL), comp, 7);
    final RfqOutcomeModel best = soft.withClientChoice(RfqOutcomeModel.ClientChoice.BEST_PRICE);
    assertEquals(RfqOutcomeModel.ClientChoice.HIT_RATE, soft.clientChoice());
    int behindWins = 0;
    for (long id = 1; id <= 2_000; id++) {
      final RfqOutcomeModel.Outcome s = soft.settle(id, TEN, 0.3, 50, 4, false);
      final RfqOutcomeModel.Outcome b = best.settle(id, TEN, 0.3, 50, 4, false);
      // the same cover and the same uniform draw under either rule (common random numbers)
      assertEquals(s.coverSpreadBp(), b.coverSpreadBp(), 0.0);
      assertEquals(s.uniform(), b.uniform(), 0.0);
      assertEquals(b.ourSpreadBp() < b.coverSpreadBp(), b.won());
      if (s.won() && s.ourSpreadBp() > s.coverSpreadBp()) {
        behindWins++;
      }
    }
    assertTrue(behindWins > 0, "the soft client accepts quotes behind the cover");
    assertThrows(IllegalArgumentException.class, () -> new RfqOutcomeModel(soft.hitRate(), comp, 7, null));
  }

  @Test
  void theRunnerSettlesWithTheConfiguredClient() {
    final Path journal = TestSupport.day(dir, LocalDate.of(2026, 1, 6));
    final BacktestConfig soft = TestSupport.config(journal, BandPoint.CENTRAL);
    final BacktestConfig best =
        with(soft, soft.outcomes().withClientChoice(RfqOutcomeModel.ClientChoice.BEST_PRICE), soft.hedgeCost(),
            soft.carry());
    final BacktestResult a = new BacktestRunner(best).run(TestSupport.fixed());
    long won = 0;
    for (final RfqRecord r : a.rfqs()) {
      if (r.quoted && r.won) {
        won++;
        assertTrue(r.ourSpreadBp <= r.coverBp, "won behind the cover: " + r.ourSpreadBp + " vs " + r.coverBp);
      }
    }
    assertTrue(won > 0, "the best-price client still trades with a quote inside the cover");
  }

  // ---------------------------------------------------------------- coupon carry

  @Test
  void aLongBondEarnsItsCouponAgainstFunding() {
    final CarryModel coupons = CarryModel.bondCoupons(Map.of(InstrumentSet.UST_10Y, 0.04));
    final PositionBook book = new PositionBook(InstrumentSet.usdDefault(), coupons);
    book.mark(InstrumentSet.UST_10Y, 100.0, 0L);
    book.trade(InstrumentSet.UST_10Y, 100 * InstrumentSet.MILLION, 100.0, 0.0, false, 0L);
    book.close(DAY);
    final Attribution a = book.attribution(InstrumentSet.UST_10Y);
    // 100mm face: coupon 4 percent ACT/365, funding 4.25 percent ACT/360, for one day
    assertEquals(100e6 * 0.04 / 365, a.carryRoll(), 1e-6);
    assertEquals(-100e6 * 0.0425 / 360, a.funding(), 1e-6);
    assertTrue(Math.abs(a.residual()) < 1e-6);
  }

  @Test
  void aShortBondPaysItsCoupon() {
    final CarryModel coupons = CarryModel.bondCoupons(Map.of(InstrumentSet.UST_2Y, 0.035, InstrumentSet.SOFR_10Y, 0.05));
    final PositionBook book = new PositionBook(InstrumentSet.usdDefault(), coupons);
    book.mark(InstrumentSet.UST_2Y, 99.5, 0L);
    book.trade(InstrumentSet.UST_2Y, -50 * InstrumentSet.MILLION, 99.5, 0.0, false, 0L);
    book.mark(InstrumentSet.SOFR_10Y, 3.95, 0L);
    book.trade(InstrumentSet.SOFR_10Y, 10 * InstrumentSet.MILLION, 3.95, 0.0, false, 0L);
    book.mark(InstrumentSet.UST_2Y, 99.6, DAY / 2);
    book.close(DAY);
    final Attribution a = book.attribution(InstrumentSet.UST_2Y);
    // coupon accrues on face whatever the price: 50mm at 3.5 percent for one day
    assertEquals(-50e6 * 0.035 / 365, a.carryRoll(), 1e-6);
    // the short earns funding on its value: half a day at 99.5, half at 99.6
    assertEquals(50e6 * 0.0425 / 360 * (0.995 + 0.996) / 2, a.funding(), 1e-6);
    assertTrue(Math.abs(a.residual()) < 1e-6);
    // swaps earn no coupon carry from this model, even with an entry
    assertEquals(0.0, book.attribution(InstrumentSet.SOFR_10Y).carryRoll(), 0.0);
    assertThrows(IllegalArgumentException.class, () -> CarryModel.bondCoupons(Map.of(1, Double.NaN)));
  }

  private static BacktestConfig with(
      final BacktestConfig c, final RfqOutcomeModel outcomes, final HedgeCostModel hedge, final CarryModel carry) {
    return new BacktestConfig(
        c.journals(), c.instruments(), c.seed(), c.bandPoint(), outcomes, c.strategyHitRate(), hedge, c.clob(), carry,
        c.calendar(), c.clientsPerTier(), c.quoteLatencyNanos(), c.clientDecisionNanos(), c.orderLatencyNanos(),
        c.markoutHorizonsNanos(), c.outputDir());
  }
}
