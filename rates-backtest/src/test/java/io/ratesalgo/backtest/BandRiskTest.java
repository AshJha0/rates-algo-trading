package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.report.BandRisk;
import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.util.Yaml;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Daily-P&L volatility, Sharpe ratio and maximum drawdown across the assumption band. */
class BandRiskTest {

  @Test
  @DisplayName("pnlRisk matches a hand-worked series")
  void handWorked() {
    // cumulative 100, 50, 80, -20, 10: peak 100, trough -20, drawdown 120
    final Stats.PnlRisk r = Stats.pnlRisk(new double[] {100, -50, 30, -100, 30});
    assertEquals(5, r.days());
    assertEquals(2.0, r.mean(), 1e-12);
    // deviations 98, -52, 28, -102, 28; squares sum 24,280; / 4 = 6,070
    assertEquals(Math.sqrt(6070.0), r.volatility(), 1e-12);
    assertEquals(2.0 / Math.sqrt(6070.0), r.sharpe(), 1e-12);
    assertEquals(r.sharpe() * Math.sqrt(252), r.annualisedSharpe(), 1e-12);
    assertEquals(120.0, r.maxDrawdown(), 1e-12);
  }

  @Test
  @DisplayName("drawdown is measured from zero, a rising series has none, a flat one has no Sharpe")
  void edges() {
    assertEquals(30.0, Stats.pnlRisk(new double[] {-10, -20, 5}).maxDrawdown(), 1e-12);
    assertEquals(0.0, Stats.pnlRisk(new double[] {1, 2, 3}).maxDrawdown(), 0.0);
    assertTrue(Double.isNaN(Stats.pnlRisk(new double[] {4, 4}).sharpe()));
    assertThrows(IllegalArgumentException.class, () -> Stats.pnlRisk(new double[] {1}));
    final Map<String, Map<String, double[]>> in = new LinkedHashMap<>();
    in.put("central", Map.of("flat", new double[] {4, 4}, "one", new double[] {7}));
    for (final Map<String, Object> row : BandRisk.rows(in)) {
      if ("flat".equals(row.get("strategy"))) {
        assertEquals("n/a", row.get("sharpe_daily"));
      } else {
        assertEquals(1L, row.get("days"));
        assertEquals(7.0, row.get("mean_daily_pnl_usd"));
      }
    }
    assertTrue(BandRisk.toMarkdown(List.of()).contains("Needs at least two session days"));
  }

  @Test
  @DisplayName("the P5-001 ledger record gives one labelled row per strategy and band point")
  void fromLedger() {
    final Map<String, Object> record =
        new Ledger(TestSupport.repoRoot().resolve("data/ledger/ledger.jsonl"))
            .entries().stream()
                .filter(e -> "P5-001".equals(e.get("prereg_id")) && "evaluation".equals(e.get("scope")))
                .findFirst()
                .orElseThrow();
    final Map<String, Object> results = Yaml.asMap(record.get("results"), "results");
    final List<Map<String, Object>> rows = BandRisk.fromExperimentResults(results);
    final Map<String, Object> band = Yaml.asMap(results.get("band"), "band");
    int expected = 0;
    for (final Object b : band.values()) {
      expected += Yaml.asMap(Yaml.asMap(b, "b").get("strategies"), "s").size();
    }
    assertEquals(expected, rows.size());
    for (final Map<String, Object> row : rows) {
      assertTrue(List.of("low", "central", "high").contains(row.get("band_point")));
      assertTrue(((Double) row.get("pnl_volatility_usd")) > 0, row.toString());
      assertTrue(((Double) row.get("max_drawdown_usd")) >= 0, row.toString());
    }
    final String md = BandRisk.toMarkdown(rows);
    assertTrue(md.contains("not evidence of edge (DR-008)"));
    assertTrue(md.contains("| central |"));
    assertTrue(BandRisk.toJson(rows).contains("\"label\""));
  }
}
