package io.ratesalgo.app.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.app.TradingProcess;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.quoting.QuotingEngine;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review 2026-11-23, Q9: the recorded back-test limits stay the default; a desk-sized set exists. */
class DeskSettingsTest {

  private static final long MINUTE = 60_000_000_000L;
  private static final long STEP = 10_000_000L;

  @TempDir Path dir;

  @Test
  void theDefaultIsTheRecordedBacktestPreset() {
    final DeskSettings d = DeskSettings.defaults();
    assertEquals(DeskSettings.LimitPreset.BACKTEST, d.limitPreset());
    assertEquals(d, new DeskSettings(10_000_000L, 250_000_000L, 2_000_000_000.0, 100_000_000L, 500.0, 2026L, 1_000.0));
    final RiskLimits l = d.limits();
    assertEquals(500_000_000_000L, l.maxPosition(1));
    assertEquals(2_000_000.0, l.bucketPv01(0));
    assertEquals(5_000_000.0, l.netPv01());
    assertEquals(20_000_000.0, l.grossPv01());
    assertEquals(50_000_000.0, l.dailyLoss());
    assertEquals(100_000_000_000L, l.maxOrderSize());
    assertEquals(1_000.0, l.quoteRatePerSecond());
    assertEquals(5_000.0, l.messageRatePerSecond());
    // nothing the recorded runs did not have
    assertEquals(Double.MAX_VALUE, l.priceCollarBp());
    assertEquals(Long.MAX_VALUE, l.fatFingerQuantity());
    assertEquals(Double.MAX_VALUE, l.rfqDv01());
    assertEquals(Double.MAX_VALUE, l.clientDv01());
    assertEquals(Double.MAX_VALUE, l.basisPv01());
    assertEquals(100_000_000_000L, d.tiers().tierOf(42).maxQuantity());
    assertEquals(DeskSettings.LimitPreset.BACKTEST, d.withQuoteRate(1e9).limitPreset());
    assertEquals(1e9, d.withQuoteRate(1e9).limits().quoteRatePerSecond());
  }

  @Test
  void theDeskSizedPresetIsInProportionToIncomeAndToTheHedger() {
    final DeskSettings d = DeskSettings.deskSized();
    assertEquals(DeskSettings.LimitPreset.DESK_SIZED, d.limitPreset());
    assertEquals(DeskSettings.LimitPreset.DESK_SIZED, d.withQuoteRate(50.0).limitPreset());
    final RiskLimits l = d.limits();
    final RiskLimits wide = DeskSettings.defaults().limits();
    final double lossMultiple = l.dailyLoss() / DeskSettings.EXPECTED_DAILY_INCOME;
    assertTrue(lossMultiple >= 3.0 && lossMultiple <= 5.0, "daily loss is " + lossMultiple + " times income");
    assertEquals(DeskSettings.DESK_RFQ_DV01, l.rfqDv01());
    assertTrue(l.bucketPv01(5) >= l.rfqDv01(), "one largest trade fits in a pillar");
    assertTrue(l.netPv01() >= 2.0 * l.rfqDv01() && l.netPv01() <= 3.0 * l.rfqDv01());
    assertTrue(l.grossPv01() >= 2.0 * l.netPv01());
    assertTrue(l.clientDv01() >= l.rfqDv01() && l.clientDv01() <= 10.0 * l.rfqDv01());
    assertTrue(l.priceCollarBp() > 5.0, "a hedge parent 5 bp from mid is inside the collar");
    assertTrue(l.fatFingerQuantity() <= l.maxOrderSize());
    assertEquals(10_000_000_000L, d.tiers().tierOf(42).maxQuantity());
    // every figure is tighter than the back-test preset's, by one to three orders of magnitude
    assertTrue(l.netPv01() * 10.0 < wide.netPv01());
    assertTrue(l.bucketPv01(0) * 10.0 < wide.bucketPv01(0));
    assertTrue(l.grossPv01() * 10.0 < wide.grossPv01());
    assertTrue(l.dailyLoss() * 100.0 < wide.dailyLoss());
    assertTrue(l.maxOrderSize() < wide.maxOrderSize());
    // the hedger's trigger, as DV01 at the baseline volatility, sits well inside the net limit
    assertTrue(20_000.0 / 6.0 < 0.1 * l.netPv01());
  }

  /**
   * The seeded day under each preset. Under the default every new control is silent: no stale mid,
   * no unhealthy timer, no stalled parent, no price mismatch, no empty Done, no DV01 refusal. Under
   * the desk-sized limits the desk still quotes, trades and hedges, and ends inside its limits.
   */
  @Test
  void theSeededDayRunsUnderBothPresets() {
    for (final DeskSettings settings : new DeskSettings[] {DeskSettings.defaults(), DeskSettings.deskSized()}) {
      final boolean sized = settings.limitPreset() == DeskSettings.LimitPreset.DESK_SIZED;
      final TradingProcess p = TradingProcess.start(new TradingProcess.Options(dir.resolve(sized ? "sized" : "wide"), 7L, settings));
      try {
        p.step(10 * MINUTE, STEP);
        final DeskGroup desk = p.group(0).desk();
        final QuotingEngine quoting = desk.desk().quoting();
        final RiskEngine risk = desk.desk().risk();
        final Hedger hedger = desk.desk().hedger(CurrencyCode.USD);
        System.out.println(String.format(Locale.ROOT,
            "%s: rfqs %d, quotes %d, fills %d, passes %d, hedges routed %d, hedge fills %d, pre-trade rejects %d, "
                + "dv01 rejects %d, breaches %d, stale-mid refusals %d, unhealthy timers %d, stalled parents %d, "
                + "price mismatches %d, empty dones %d, net dv01 %.0f, pnl %.0f",
            settings.limitPreset(), quoting.rfqs(), quoting.quotesSent(), quoting.fills(), quoting.passes(),
            desk.hedgesRouted(), desk.hedgeFills(), risk.preTradeRejects(), risk.dv01Rejects(), risk.breachCount(),
            hedger.staleMidRefusals(), hedger.unhealthyTimers(), hedger.stalledParents(), quoting.priceMismatches(),
            quoting.emptyDones(), risk.netPv01(CurrencyCode.USD), risk.dailyPnl(CurrencyCode.USD)));
        assertNull(p.failure());
        assertTrue(quoting.quotesSent() > 0 && quoting.fills() > 0, "quotes and fills");
        assertTrue(desk.hedgesRouted() > 0 && desk.hedgeFills() > 0, "hedges");
        assertEquals(0L, quoting.priceMismatches(), "the venue trades at the quoted price");
        assertEquals(0L, quoting.emptyDones());
        assertEquals(0L, hedger.stalledParents());
        if (sized) {
          assertTrue(Math.abs(risk.netPv01(CurrencyCode.USD)) <= settings.limits().netPv01(), "inside the net limit");
          assertTrue(risk.killSwitch().isActive(), "no loss-limit kill on the seeded day");
        } else {
          assertEquals(36L, quoting.rfqs(), "the recorded smoke day");
          assertEquals(27L, quoting.quotesSent());
          assertEquals(16L, quoting.fills());
          assertEquals(210L, desk.hedgesRouted());
          assertEquals(213L, desk.hedgeFills());
          assertEquals(0L, hedger.staleMidRefusals());
          assertEquals(0L, hedger.unhealthyTimers());
          assertEquals(0L, risk.dv01Rejects());
        }
      } finally {
        p.close();
      }
    }
  }
}
