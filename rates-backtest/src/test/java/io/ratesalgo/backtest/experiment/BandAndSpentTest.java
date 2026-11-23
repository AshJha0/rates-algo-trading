package io.ratesalgo.backtest.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.sim.SimParams.Scalar;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Band rows, spent data and the summary's arithmetic on records with known numbers. */
class BandAndSpentTest {

  @Test
  void eachBandRowChangesItsOwnParameterOnly() {
    final SimParams c = SimParams.loadDefault();
    final List<BandParameters.Row> rows =
        BandParameters.rows(c, List.of("all_in_cost", "informed_fraction", "drift_tau", "level_vol", "spread_factor", "impact",
            "basis", "taker_skill", "impact_half_life"));
    assertEquals(19, rows.size());
    assertEquals(BandParameters.CENTRAL, rows.get(0).label());
    assertEquals(c, rows.get(0).params());
    final Map<String, SimParams> by = new LinkedHashMap<>();
    for (final BandParameters.Row r : rows) {
      by.put(r.label(), r.params());
    }
    assertEquals(0.05, by.get("all_in_cost:low").get(Scalar.ALL_IN_COST_BP), 0.0);
    assertEquals(c.get(Scalar.MEDIAN_HALF_SPREAD_BP), by.get("all_in_cost:high").get(Scalar.MEDIAN_HALF_SPREAD_BP), 0.0);
    assertTrue(by.get("all_in_cost:high").get(Scalar.EXPECTED_ADVERSE_MOVE_BP) < c.get(Scalar.EXPECTED_ADVERSE_MOVE_BP));
    assertEquals(0.2, by.get("informed_fraction:low").get(Scalar.INFORMED_FRACTION), 0.0);
    assertEquals(c.get(Scalar.EXPECTED_ADVERSE_MOVE_BP), by.get("informed_fraction:high").get(Scalar.EXPECTED_ADVERSE_MOVE_BP), 1e-15);
    assertEquals(60.0, by.get("drift_tau:high").get(Scalar.DRIFT_TIME_CONSTANT_S), 0.0);
    assertEquals(0.76 * c.factorSdPerSqrtMinute(1), by.get("level_vol:low").factorSdPerSqrtMinute(1), 1e-15);
    assertEquals(0.0072, by.get("spread_factor:low").get(Scalar.SPREAD_SD_PER_SQRT_MINUTE), 0.0001);
    assertEquals(0.0312, by.get("spread_factor:high").get(Scalar.SPREAD_SD_PER_SQRT_MINUTE), 0.0001);
    assertEquals(1.5, by.get("impact:high").get(Scalar.IMPACT_Y), 0.0);
    assertEquals(0.4, by.get("basis:high").get(Scalar.BASIS_STATIONARY_SD_BP), 0.0);
    assertEquals(0.0, by.get("taker_skill:low").get(Scalar.TAKER_SIGN_SKILL), 0.0);
    assertEquals(5.0, by.get("impact_half_life:low").get(Scalar.IMPACT_HALF_LIFE_S), 0.0);
    // a row is rebuilt from its label alone, as each worker does
    assertEquals(by.get("impact:high").get(Scalar.IMPACT_Y), BandParameters.row(c, "impact:high").params().get(Scalar.IMPACT_Y), 0.0);
    assertEquals(c, BandParameters.row(c, "central").params());
    assertNotEquals(c.get(Scalar.IMPACT_Y), BandParameters.row(c, "impact:low").params().get(Scalar.IMPACT_Y));
    assertThrows(IllegalArgumentException.class, () -> BandParameters.row(c, "impact"));
    assertThrows(IllegalArgumentException.class, () -> BandParameters.row(c, "impact:middle"));
    assertThrows(IllegalArgumentException.class, () -> BandParameters.row(c, "speed:low"));
    assertThrows(IllegalArgumentException.class, () -> BandParameters.rows(c, List.of("speed")));
    assertEquals(10, BandParameters.names().size());
    // the lower winner band starts; the upper one is refused at informed fraction 0.5 (design D14)
    assertEquals(0.1228, BandParameters.row(c, "winner_half_spread:low").params().get(Scalar.MEDIAN_HALF_SPREAD_BP), 0.0);
    final IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> BandParameters.rows(c, List.of("winner_half_spread")));
    assertTrue(e.getMessage().startsWith("band row winner_half_spread:high is refused"), e.getMessage());
  }

  @Test
  void spentDataIsRefusedByBaseAndBySeed() {
    assertNull(SpentData.registeredRefusal(30_000_000L));
    assertNull(SpentData.registeredRefusal(32_000_000L));
    assertTrue(SpentData.registeredRefusal(8_000_000L).contains("is spent"));
    assertTrue(SpentData.registeredRefusal(5_000_000L).contains("is spent"));
    assertTrue(SpentData.registeredRefusal(5_000_123L).contains("is spent"));
    assertTrue(SpentData.registeredRefusal(28_000_000L).contains("acceptance"));
    assertTrue(SpentData.registeredRefusal(29_000_000L).contains("pilot base"));
    assertTrue(SpentData.registeredRefusal(31_500_000L).contains("not a simulator v2 base"));
    assertTrue(SpentData.registeredRefusal(1_000_000L).contains("not a simulator v2 base"));
    assertNull(SpentData.spentRefusal(29_000_000L));
    // AUDIT-26's April holdout session of 2026-04-06 and an AUDIT-1n session of February
    final long april = 8_000_000L + LocalDate.of(2026, 4, 6).toEpochDay();
    assertTrue(SpentData.seedRefusal(april).contains("label date 2026-04-06"), SpentData.seedRefusal(april));
    assertTrue(SpentData.seedRefusal(5_000_000L + LocalDate.of(2026, 2, 2).toEpochDay()).contains("spent session"));
    assertTrue(SpentData.seedRefusal(8_000_001L).contains("spent base"));
    assertNull(SpentData.seedRefusal(29_000_001L));
  }

  private static Map<String, Object> session(final long seed, final double pnlA, final double pnlB, final long quotedB) {
    final Map<String, Object> arms = new LinkedHashMap<>();
    arms.put("a", arm(pnlA, 10));
    arms.put("b", arm(pnlB, quotedB));
    final Map<String, Object> rows = new LinkedHashMap<>();
    rows.put("central", Map.of("journal_sha256", "j" + seed, "arms", arms));
    rows.put("impact:low", Map.of("journal_sha256", "k" + seed, "arms", arms));
    final Map<String, Object> s = new LinkedHashMap<>();
    s.put("seed", seed);
    s.put("rows", rows);
    return s;
  }

  private static Map<String, Object> arm(final double pnl, final long quoted) {
    final Map<String, Object> m = new LinkedHashMap<>();
    for (final String line : ExperimentSummary.LINES) {
      m.put(line, 0.0);
    }
    m.put("spread_capture", pnl);
    m.put("position_pnl", pnl);
    m.put("client_dv01_usd", 1_000.0);
    m.put("hedge_dv01_usd", 500.0);
    m.put("rfqs", 20L);
    m.put("quoted", quoted);
    m.put("won", 5L);
    m.put("withdrawn", 0L);
    m.put("hedge_fills", 2L);
    m.put("wall_ms", 1000L);
    m.put("output_chain", "ab");
    return m;
  }

  @Test
  void theSummaryComputesThePairedComparisonOnce() {
    final SessionDesign design =
        new SessionDesign("X", 32_000_000L, 0, 4, "a", List.of("b"), List.of("impact"), 2, 200, 5L, 100.0, 29_000_000L, false,
            Map.of("quoted_share", 0.8));
    final Map<Long, Map<String, Object>> sessions = new LinkedHashMap<>();
    final double[] a = {100, -50, 30, 80};
    final double[] b = {110, -45, 41, 92};
    for (int n = 0; n < 4; n++) {
      sessions.put(32_000_000L + n, session(32_000_000L + n, a[n], b[n], 8));
    }
    final Map<String, Object> r = ExperimentSummary.compute(design, List.of("central", "impact:low"), sessions);
    @SuppressWarnings("unchecked")
    final Map<String, Object> c = (Map<String, Object>) ((Map<String, Object>) r.get("primary")).get("b");
    final Stats.Paired p = Stats.pairedT(b, a);
    assertEquals(p.meanDifference(), (Double) c.get("mean_difference"), 1e-9);
    assertEquals(p.p(), (Double) c.get("paired_p"), 1e-15);
    assertEquals(2L, c.get("block_sessions"));
    // 9.5 USD on 1,000 USD of client DV01 per session
    assertEquals(0.0095, (Double) c.get("mean_difference_bp_of_baseline_client_dv01"), 1e-12);
    @SuppressWarnings("unchecked")
    final Map<String, Object> guards = (Map<String, Object>) c.get("guards");
    assertEquals(0.8, (Double) guards.get("quoted_share_ratio"), 1e-12);
    assertEquals(1.25, (Double) guards.get("hit_rate_ratio"), 1e-12);
    assertEquals(1.0, (Double) guards.get("client_dv01_ratio"), 1e-12);
    assertEquals(Map.of("b", Map.of("central", 1L, "impact:low", 1L)), r.get("signs_by_band"));
    assertEquals(ExperimentSummary.journalDigest(sessions), ExperimentSummary.journalDigest(new LinkedHashMap<>(sessions)));
    assertEquals(64, ExperimentSummary.outputDigest(sessions).length());
    // identical arms: the test is undefined and said so, never p = 1
    final Map<Long, Map<String, Object>> same = new LinkedHashMap<>();
    for (int n = 0; n < 4; n++) {
      same.put(32_000_000L + n, session(32_000_000L + n, a[n], a[n], 10));
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> u =
        (Map<String, Object>) ((Map<String, Object>) ExperimentSummary.compute(design, List.of("central"), same).get("primary")).get("b");
    assertEquals("undefined", u.get("paired_p"));
  }
}
