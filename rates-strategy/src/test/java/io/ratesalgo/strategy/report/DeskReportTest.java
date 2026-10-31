package io.ratesalgo.strategy.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.strategy.Support;
import io.ratesalgo.strategy.experiment.DeskExperiment;
import io.ratesalgo.strategy.experiment.DeskFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The desk view: the waterfall reconciles to position P&amp;L, and the report renders. */
class DeskReportTest {

  @TempDir Path dir;

  @Test
  @DisplayName("waterfall: the lines sum to position P&L less the residual, to the cent")
  void waterfallReconciles() {
    final Path journal = Support.day(dir, LocalDate.of(2026, 2, 19));
    final BacktestConfig cfg = Support.config(journal);
    final DeskFactory f = Support.factory(BandPoint.CENTRAL);
    for (final String name : new String[] {DeskFactory.BASELINE, DeskFactory.DESK_ALL}) {
      final BacktestResult r =
          new BacktestRunner(cfg)
              .run(DeskFactory.BASELINE.equals(name) ? f.baseline() : f.treatment(name, f.newTiering()));
      final DeskMetrics m = DeskMetrics.of(r, cfg.instruments(), DeskExperiment.settings(f));
      double sum = 0;
      for (final String k : DeskReport.WATERFALL) {
        sum += m.get(k);
      }
      assertEquals(Math.round(r.total().positionPnl() * 100), Math.round((sum + m.get("residual")) * 100), name);
      assertTrue(m.get("client_dv01_traded") > 0);
      assertEquals(m.get("pnl") / m.get("client_dv01_traded"), m.get("pnl_per_dv01"), 1e-12);
      assertEquals(
          m.get("adverse_selection"),
          m.get("adverse_tier_1") + m.get("adverse_tier_2") + m.get("adverse_tier_3"),
          1e-6);
      assertTrue(m.get("balance_sheet_charge") <= 0);
      assertTrue(m.get("inventory_half_life_s") >= 0);
      assertTrue(m.get("risk_charge") >= 0);
      assertEquals(m.get("funding") + m.get("carry") + m.get("balance_sheet_charge"), m.get("funding_net"), 1e-9);
      assertTrue(m.get("carry") != 0.0, "coupon carry accrues on bond inventory");
      assertTrue(m.values().size() > 25);
      assertTrue(!m.hitRateCounts().isEmpty());
      final DeskReport report = new DeskReport("2026-02-19", name, m);
      final Map<String, Object> parsed = Yaml.parseMap(report.toJson(), "desk report");
      assertTrue(parsed.containsKey("waterfall") && parsed.containsKey("hit_rate") && parsed.containsKey("headline"));
      final String md = report.toMarkdown();
      assertTrue(md.contains("| Waterfall line | USD |"));
      assertTrue(md.contains("tier 3"));
      assertTrue(md.contains("Simulator consistency check"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeskMetrics.of(
                    new BacktestRunner(cfg).run(f.baseline()), cfg.instruments(), DeskExperiment.settings(f))
                .get("nope"));
  }

  @Test
  @DisplayName("half-life: median time for |net DV01| to halve after each client fill, censored at the end")
  void halfLife() {
    final long s = 1_000_000_000L;
    // fills at 0, 10, 20 s: net 100, 40 (halved by 10 s), 200 (never halves: censored at 100 s)
    final double h =
        DeskMetrics.halfLife(
            new long[] {0, 10 * s, 20 * s}, new double[] {100, 40, 200}, new boolean[] {true, true, true}, 100 * s);
    // lives: 10 s (100 -> 40), 90 s (40 never falls to 20: censored), 80 s (200, censored)
    assertEquals(80.0, h, 1e-9);
    assertEquals(
        0.0, DeskMetrics.halfLife(new long[] {0}, new double[] {0}, new boolean[] {true}, s), 0.0);
    assertEquals(
        10.0,
        DeskMetrics.halfLife(new long[] {0, 10 * s}, new double[] {100, 50}, new boolean[] {true, false}, 90 * s),
        1e-9);
  }

  @Test
  @DisplayName("cli: writes desk_report.json and desk_report.md for one session")
  void cliWritesReport() throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final DeskReport report =
        DeskReportCli.run(
            new String[] {
              "--repo", Support.repoRoot().toString(), "--date", "2026-02-20", "--strategy", "baseline",
              "--out", dir.resolve("out").toString()
            },
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
    assertTrue(Files.readString(dir.resolve("out/desk_report.md"), StandardCharsets.UTF_8).contains("Desk view"));
    assertTrue(Files.isRegularFile(dir.resolve("out/desk_report.json")));
    assertEquals("baseline", report.toMap().get("strategy"));
    DeskReportCli.run(
        new String[] {
          "--repo", Support.repoRoot().toString(), "--date", "2026-02-20", "--strategy", "tiering", "--band", "low",
          "--out", dir.resolve("out2").toString()
        },
        new PrintStream(bytes, true, StandardCharsets.UTF_8));
    assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("desk_report.md"));
    assertThrows(IllegalArgumentException.class, () -> DeskReportCli.run(new String[] {"x", "y"}, System.out));
  }
}
