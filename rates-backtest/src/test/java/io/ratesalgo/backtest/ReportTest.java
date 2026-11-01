package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.report.ReportCli;
import io.ratesalgo.backtest.synth.SyntheticDayCli;
import io.ratesalgo.backtest.util.Yaml;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The daily report runs unattended from a journal and a config (P5 gate). */
class ReportTest {

  @TempDir Path dir;

  @Test
  @DisplayName("daily report runs unattended from a journal and a config")
  void runsUnattended() throws IOException {
    final Path repo = TestSupport.repoRoot();
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final Path journal = dir.resolve("day.raj");
    final Path journal2 = dir.resolve("day2.raj");
    final Path latency = dir.resolve("latency.json");
    Files.writeString(latency, "{\"machine\":\"test\",\"percentiles_ns\":{\"p50\":1200,\"p99\":4800}}");
    final Path config = dir.resolve("report.yaml");
    Files.writeString(
        config,
        Files.readString(repo.resolve("data/experiments/daily_report.yaml"))
            .replace("latency_json: target/latency/latency.json", "latency_json: " + latency.toString().replace('\\', '/')));
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      assertEquals(
          0,
          SyntheticDayCli.run(
              new String[] {"--date", "2026-02-02", "--out", journal.toString(), "--truth", dir.resolve("truth.csv").toString(), "--repo", repo.toString()},
              out));
      assertEquals(
          0,
          SyntheticDayCli.run(
              new String[] {"--date", "2026-02-03", "--out", journal2.toString(), "--truth", dir.resolve("truth2.csv").toString(), "--repo", repo.toString()},
              out));
      assertEquals(
          0,
          ReportCli.run(
              new String[] {"--config", config.toString(), "--journal", journal + "," + journal2, "--out", dir.resolve("out").toString(), "--repo", repo.toString()},
              out));
      assertEquals(1, ReportCli.run(new String[] {"--config", "x"}, out));
      assertEquals(1, SyntheticDayCli.run(new String[] {}, out));
    }
    for (final String point : new String[] {"low", "central", "high"}) {
      final Path d = dir.resolve("out").resolve(point);
      final String md = Files.readString(d.resolve("report.md"));
      for (final String heading :
          new String[] {
            "P&L by product", "Hit rate by client tier", "Markouts by client tier", "Hedge slippage", "Router fill quality",
            "Latency percentiles", "Limit utilisation", "Client analytics", "Response time",
            "P&L risk across the assumption band"
          }) {
        assertTrue(md.contains("## " + heading), point + " missing " + heading);
      }
      final Map<String, Object> json = Yaml.loadMap(d.resolve("report.json"));
      assertEquals(point, json.get("band_point"));
      assertEquals(true, Yaml.asMap(json.get("latency"), "latency").get("available"));
      assertTrue(Files.size(d.resolve("results.raj")) > 0);
      assertTrue(Files.readString(d.resolve("fills.csv")).lines().count() > 10);
    }
    final Map<String, Object> risk = Yaml.loadMap(dir.resolve("out").resolve("band_risk.json"));
    assertTrue(String.valueOf(risk.get("label")).contains("Simulator consistency check"));
    final List<Object> rows = Yaml.asList(risk.get("rows"), "rows");
    assertEquals(3, rows.size());
    for (final Object o : rows) {
      final Map<String, Object> row = Yaml.asMap(o, "row");
      assertEquals(2L, ((Number) row.get("days")).longValue());
      assertTrue(row.containsKey("sharpe_daily") && row.containsKey("max_drawdown_usd") && row.containsKey("pnl_volatility_usd"));
    }
    assertTrue(Files.readString(dir.resolve("out").resolve("central").resolve("report.md")).contains("| central |"));
    assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("rfqs"));
    assertTrue(Files.readString(dir.resolve("truth.csv")).startsWith("rfq_id,"));
  }
}
