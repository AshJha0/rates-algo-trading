package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.ClobFillModel;
import io.ratesalgo.backtest.model.CompetitorModel;
import io.ratesalgo.backtest.model.EventCalendar;
import io.ratesalgo.backtest.model.HedgeCostModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.model.Preset;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.model.RfqOutcomeModel;
import io.ratesalgo.backtest.strategy.ModelSpreadStrategy;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.backtest.util.SplitMix64;
import io.ratesalgo.backtest.util.Yaml;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelsTest {

  private static final Presets PRESETS = TestSupport.presets();
  private static final Instrument TEN = InstrumentSet.usdDefault().get(InstrumentSet.SOFR_10Y);

  @Test
  void presetsFileFollowsTheContract() {
    for (final String name :
        List.of("usd_rfq_hit_rate", "usd_rfq_competition", "usd_rfq_flow", "usd_synthetic_market", "usd_hedge_cost")) {
      assertTrue(PRESETS.contains(name), name);
    }
    final Preset hit = PRESETS.get(HitRateModel.PRESET);
    final Preset.Band band = hit.band(HitRateModel.SLOPE);
    assertTrue(band.low() > band.central() && band.central() > band.high(), "slope band ordered");
    assertTrue(hit.parameters().get("intercept").standardError() > 0);
    assertTrue(Double.isNaN(PRESETS.get("usd_hedge_cost").parameters().get("fee_per_lot").standardError()));
    assertTrue(hit.value("oos_log_loss_model") < hit.value("oos_log_loss_constant"));
    assertThrows(IllegalArgumentException.class, () -> PRESETS.get("missing"));
    assertThrows(IllegalArgumentException.class, () -> hit.value("missing"));
    assertThrows(IllegalArgumentException.class, () -> hit.band("missing"));
  }

  @Test
  void presetParserNamesWhatIsMissing() {
    final IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Presets.parse(Yaml.parseMap("presets:\n- name: x\n  fitted_from: a\n", "t"), "t"));
    assertTrue(e.getMessage().contains("data_range"), e.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Presets.parse(
                Yaml.parseMap(
                    """
                    presets:
                    - name: x
                      fitted_from: a
                      data_range: {start: 2026-01-01, end: 2026-01-02}
                      sample_size: 1
                      method: m
                      parameters: {a: {value: 1}}
                    """,
                    "t"),
                "t"));
    assertThrows(IllegalArgumentException.class, () -> new Preset.Band(1, 0, 2, ""));
  }

  @Test
  void hitRateFallsWithSpreadToCoverAndBandChangesTheSlope() {
    final Preset p = PRESETS.get(HitRateModel.PRESET);
    final HitRateModel low = HitRateModel.fromPreset(p, BandPoint.LOW);
    final HitRateModel high = HitRateModel.fromPreset(p, BandPoint.HIGH);
    assertTrue(low.winProbability(0.0, 50, 10, false, 4) > low.winProbability(0.5, 50, 10, false, 4));
    final double dropLow = low.winProbability(0, 50, 10, false, 4) - low.winProbability(0.3, 50, 10, false, 4);
    final double dropHigh = high.winProbability(0, 50, 10, false, 4) - high.winProbability(0.3, 50, 10, false, 4);
    assertTrue(dropHigh > dropLow);
    assertEquals(BandPoint.HIGH, high.bandPoint());
    assertThrows(IllegalArgumentException.class, () -> low.winProbability(0, 0, 10, false, 4));
    assertThrows(IllegalArgumentException.class, () -> new HitRateModel(0, 0.1, 0, 0, 0, 0, BandPoint.LOW));
  }

  @Test
  void outcomesUseCommonRandomNumbersPerRfq() {
    final Preset p = PRESETS.get(HitRateModel.PRESET);
    final CompetitorModel comp = CompetitorModel.fromPreset(PRESETS.get(CompetitorModel.PRESET));
    final RfqOutcomeModel m = new RfqOutcomeModel(HitRateModel.fromPreset(p, BandPoint.CENTRAL), comp, 7);
    final RfqOutcomeModel.Outcome a = m.settle(42, TEN, 0.2, 50, 4, false);
    final RfqOutcomeModel.Outcome b = m.settle(42, TEN, 0.5, 50, 4, false);
    assertEquals(a.uniform(), b.uniform());
    assertEquals(a.coverSpreadBp(), b.coverSpreadBp());
    assertTrue(a.winProbability() > b.winProbability());
    assertThrows(IllegalArgumentException.class, () -> comp.coverBp(TEN, 50, 1, new SplitMix64(1)));
    assertTrue(comp.medianBp(InstrumentSet.usdDefault().get(InstrumentSet.SOFR_30Y), 50) > comp.medianBp(TEN, 50));
    assertTrue(comp.medianBp(InstrumentSet.usdDefault().get(InstrumentSet.UST_10Y), 50) < comp.medianBp(TEN, 50));
    int wins = 0;
    for (long id = 1; id <= 2000; id++) {
      if (m.settle(id, TEN, 0.0, 50, 3, false).won()) {
        wins++;
      }
    }
    assertTrue(wins > 300 && wins < 1700, "wins " + wins);
    assertEquals(m.hitRate().slope(), p.band(HitRateModel.SLOPE).central());
    assertEquals(comp, m.competitors());
  }

  @Test
  void hedgeCostRoundsAgainstUsAndCapsImpact() {
    final Instrument ty = InstrumentSet.usdDefault().get(InstrumentSet.TY_FUTURE);
    final HedgeCostModel h = new HedgeCostModel(0.001, 1.5, 1.0);
    final long mid = Prices.toMantissa(112.0);
    final HedgeCostModel.Fill buy = h.fill(ty, true, 100 * ty.lotSize(), mid, ty.tickMantissa() / 2);
    final HedgeCostModel.Fill sell = h.fill(ty, false, 100 * ty.lotSize(), mid, ty.tickMantissa() / 2);
    assertTrue(buy.priceMantissa() > mid && sell.priceMantissa() < mid);
    assertEquals(0, buy.priceMantissa() % ty.tickMantissa());
    assertEquals(150.0, buy.fee(), 1e-9);
    final HedgeCostModel.Fill huge = h.fill(ty, true, 100_000 * ty.lotSize(), mid, 0);
    assertTrue(huge.priceMantissa() - mid <= ty.tickMantissa() * 2);
    assertThrows(IllegalArgumentException.class, () -> h.fill(ty, true, 0, mid, 0));
    assertThrows(IllegalArgumentException.class, () -> new HedgeCostModel(-1, 0, 0));
    assertEquals(3.0, h.fee(ty, 2 * ty.lotSize()), 1e-12);
  }

  @Test
  void clobQueuePosition() {
    final ClobFillModel m = new ClobFillModel(0.5);
    final ClobFillModel.Resting r = m.join(true, 100, 100, 1000);
    assertEquals(1000, r.ahead(), 0.0);
    assertFalse(r.onBook(100, 600, 101)); // 400 decrease, half trades
    assertEquals(800, r.ahead(), 1e-9);
    assertFalse(r.onBook(100, 600, 101));
    assertTrue(r.onBook(100, 0, 101) || r.ahead() <= 500);
    final ClobFillModel.Resting cross = m.join(false, 105, 105, 10);
    assertTrue(cross.onBook(105, 10, 106) || cross.onBook(105, 10, 105));
    final ClobFillModel.Resting through = m.join(true, 100, 100, 50);
    assertTrue(through.onBook(99, 10, 0));
    final ClobFillModel.Resting improve = m.join(true, 101, 100, 50);
    assertTrue(improve.onBook(101, 5, 0));
    assertEquals(101, improve.price());
    assertTrue(improve.buy());
    assertEquals(0.5, m.tradeShare());
    assertThrows(IllegalArgumentException.class, () -> new ClobFillModel(0));
  }

  @Test
  void eventCalendarWindows() {
    final EventCalendar cal = EventCalendar.forDay(LocalDate.of(2026, 2, 2), -5, List.of(510.0), 15.0);
    final long event = cal.events()[0];
    assertEquals(java.time.Instant.parse("2026-02-02T13:30:00Z").getEpochSecond() * 1_000_000_000L, event);
    assertTrue(cal.inWindow(event + 14 * 60_000_000_000L));
    assertFalse(cal.inWindow(event + 16 * 60_000_000_000L));
    assertEquals(15 * 60_000_000_000L, cal.halfWidthNanos());
  }

  @Test
  void modelSpreadWidensForToxicTiersAndExpectedMinimumIsTabulated() {
    final ModelSpreadStrategy m =
        new ModelSpreadStrategy(
            HitRateModel.fromPreset(PRESETS.get(HitRateModel.PRESET), BandPoint.CENTRAL),
            CompetitorModel.fromPreset(PRESETS.get(CompetitorModel.PRESET)),
            new double[] {0.0, 0.1, 0.4},
            0.1,
            0.1,
            2.0,
            0.01,
            100_000,
            4);
    final double tier1 = m.optimalHalfSpreadBp(TEN, 50, 1, 4, false);
    final double tier3 = m.optimalHalfSpreadBp(TEN, 50, 3, 4, false);
    assertTrue(tier3 > tier1, tier1 + " vs " + tier3);
    assertTrue(m.expectedEdgeBp(tier1, TEN, 50, 1, 4, false) > 0);
    for (int k = 1; k <= 8; k++) {
      assertTrue(ModelSpreadStrategy.expectedMinNormal(k + 1) <= ModelSpreadStrategy.expectedMinNormal(k));
    }
    assertEquals(ModelSpreadStrategy.NAME, m.name());
  }

  @Test
  void instrumentsAndPrices() {
    final InstrumentSet set = InstrumentSet.usdDefault();
    assertEquals(7, set.all().size());
    assertThrows(IllegalArgumentException.class, () -> set.get(999));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InstrumentSet(List.of(TEN, TEN)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Instrument(0, "x", Instrument.Kind.SWAP, 1, 1, 1, 1, 1, 0));
    assertEquals(81_100.0, 100 * InstrumentSet.MILLION * TEN.dv01PerUnit(), 1e-6);
    assertEquals(0.3, TEN.priceToBp(TEN.bpToPrice(0.3)), 1e-12);
    assertEquals(100, Prices.roundToTick(150, 100, false));
    assertEquals(200, Prices.roundToTick(150, 100, true));
    assertEquals(-200, Prices.roundToTick(-150, 100, false));
    assertEquals(1.23, Prices.cents(1.2349), 0.0);
    assertThrows(IllegalArgumentException.class, () -> Prices.toMantissa(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> Prices.roundToTick(1, 0, true));
  }
}
