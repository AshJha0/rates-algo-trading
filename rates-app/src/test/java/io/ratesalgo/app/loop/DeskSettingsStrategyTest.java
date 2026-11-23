package io.ratesalgo.app.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.TradingProcess;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.quoting.QuotingEngine;
import io.ratesalgo.quoting.StrategyConfig;
import io.ratesalgo.quoting.TierPriors;
import io.ratesalgo.quoting.TierTable;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round 5: the strategy V2 preset turns the capabilities on together; the default preset turns
 * none of them on. The wiring run checks that the wired process builds and runs the V2 desk; it
 * reads no P&amp;L (the pre-registrations R5-001 to R5-006 decide what is read, and when).
 */
class DeskSettingsStrategyTest {

  private static final long MINUTE = 60_000_000_000L;

  @TempDir Path dir;

  @Test
  void theDefaultPresetTurnsNothingOn() {
    final DeskSettings d = DeskSettings.defaults();
    assertEquals(DeskSettings.StrategyPreset.DEFAULT, d.strategyPreset());
    final StrategyConfig sc = d.quoting().strategy();
    assertFalse(sc.pricingActive());
    assertFalse(sc.factorCovariance() || sc.factorHedging() || sc.tickHedgeCost() || sc.volatilityTtl());
    assertTrue(Double.isNaN(sc.volatilityReferenceTenor()));
    assertFalse(d.hedger().internalising());
    assertEquals(DeskSettings.StrategyPreset.DEFAULT, DeskSettings.deskSized().strategyPreset());
    assertEquals(d, new DeskSettings(10_000_000L, 250_000_000L, 2_000_000_000.0, 100_000_000L, 500.0, 2026L, 1_000.0,
        DeskSettings.LimitPreset.BACKTEST));
  }

  @Test
  void strategyV2TurnsEveryCapabilityOnWithTheBacktestLimits() {
    final DeskSettings v2 = DeskSettings.strategyV2();
    assertEquals(DeskSettings.StrategyPreset.V2, v2.strategyPreset());
    assertEquals(DeskSettings.LimitPreset.BACKTEST, v2.limitPreset(), "only the strategy differs from the default");
    assertEquals(DeskSettings.defaults().limits().netPv01(), v2.limits().netPv01());
    final StrategyConfig sc = v2.quoting().strategy();
    assertTrue(sc.coverPricing() && sc.reservationPrice() && sc.factorCovariance() && sc.factorHedging()
        && sc.tickHedgeCost() && sc.markoutStore() && sc.tickRounding() && sc.volatilityTtl());
    assertEquals(10.0, sc.volatilityReferenceTenor());
    final Hedger.Config h = v2.hedger();
    assertTrue(h.internalising());
    assertTrue(DeskSettings.V2_TIME_STOP_NANOS < 60_000_000_000L, "inside the stalled-parent timeout");
    assertTrue(DeskSettings.V2_BUDGET_STD_DEV > 20_000.0, "above the trigger");
    // more than one tier, matching the markout store's priors
    final TierTable t = v2.tiers();
    assertEquals(TierPriors.TIER_A, t.tierOf(2001).tier());
    assertEquals(TierPriors.TIER_B, t.tierOf(2003).tier());
    assertEquals(TierPriors.TIER_C, t.tierOf(2005).tier());
    assertEquals(TierPriors.TIER_B, t.tierOf(42).tier());
    assertEquals(DeskSettings.defaults().tiers().tierOf(42).maxQuantity(), t.tierOf(2001).maxQuantity());
    final DeskSettings sizedV2 = v2.withLimits(DeskSettings.LimitPreset.DESK_SIZED);
    assertEquals(DeskSettings.StrategyPreset.V2, sizedV2.strategyPreset());
    assertEquals(10_000_000_000L, sizedV2.tiers().tierOf(2005).maxQuantity());
    assertEquals(DeskSettings.StrategyPreset.V2, v2.withQuoteRate(5.0).strategyPreset());
    assertEquals(DeskSettings.StrategyPreset.DEFAULT, v2.withStrategy(DeskSettings.StrategyPreset.DEFAULT).strategyPreset());
  }

  @Test
  void theWiredProcessBuildsAndRunsTheV2Desk() {
    final TradingProcess p = TradingProcess.start(new TradingProcess.Options(dir.resolve("v2"), 7L, DeskSettings.strategyV2()));
    try {
      p.step(5 * MINUTE, 10_000_000L);
      assertNull(p.failure());
      final QuotingEngine quoting = p.group(0).desk().desk().quoting();
      assertNotNull(quoting.cover(), "cover pricing wired");
      assertNotNull(quoting.markouts(), "markout store wired");
      assertTrue(quoting.rfqs() > 0);
    } finally {
      p.close();
    }
  }
}
