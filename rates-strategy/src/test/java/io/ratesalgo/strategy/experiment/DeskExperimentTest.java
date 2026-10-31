package io.ratesalgo.strategy.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.strategy.Support;
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

/** The experiment harness: band scenarios, statistics, refusals and a full two-day run into a ledger. */
class DeskExperimentTest {

  @TempDir Path dir;

  @Test
  @DisplayName("band: dealer range and informed share move with the band point")
  void bandScenarios() {
    final Presets low = Support.presets(BandPoint.LOW);
    final Presets high = Support.presets(BandPoint.HIGH);
    final Presets central = Support.presets(BandPoint.CENTRAL);
    assertEquals(4.0, low.get("usd_synthetic_market").value("dealers_max"), 0.0);
    assertEquals(6.0, high.get("usd_synthetic_market").value("dealers_max"), 0.0);
    assertEquals(3.0, high.get("usd_synthetic_market").value("dealers_min"), 0.0);
    final double c3 = central.get("usd_synthetic_market").value("tier_3_informed_share");
    assertEquals(0.45, c3, 1e-12);
    assertEquals(0.225, low.get("usd_synthetic_market").value("tier_3_informed_share"), 1e-12);
    assertEquals(0.675, high.get("usd_synthetic_market").value("tier_3_informed_share"), 1e-12);
  }

  @Test
  @DisplayName("paired test: matches the t formula; all-zero differences give p = 1")
  void pairedTest() {
    final double[] d = {1, 2, 3, 4, 2};
    final double t = Stats.mean(d) / Math.sqrt(Stats.variance(d) / d.length);
    assertEquals(Stats.studentTwoSidedP(t, 4), DeskExperiment.pairedP(d), 1e-15);
    assertEquals(1.0, DeskExperiment.pairedP(new double[] {0, 0, 0}), 0.0);
    assertEquals(0.0, DeskExperiment.pairedP(new double[] {2, 2, 2}), 0.0);
  }

  @Test
  @DisplayName("factory: derived hedge cost and impact follow the hedge cost model; unknown treatments refused")
  void factory() {
    final DeskFactory f = Support.factory(BandPoint.CENTRAL);
    // half a 1/64 tick on a 100,000 contract (7.8125) plus 1.50 fee, per 71.5 DV01
    assertEquals((7.8125 + 1.5) / 71.5, f.hedgeCostPerDv01(), 1e-9);
    assertEquals(0.0002 * 15.625 / (71.5 * 71.5), f.impactPerDv01Squared(), 1e-15);
    assertEquals(0.35 / Math.sqrt(60), f.sigmaBpPerSqrtSecond(), 1e-12);
    assertEquals(4, f.clientsPerTier());
    assertEquals(1_000_000, f.limitDv01(), 0.0);
    assertThrows(IllegalArgumentException.class, () -> f.treatment("nope", null));
  }

  private static String prereg(final String id, final String treatment, final String configSha) {
    return String.join(
        "\n",
        "prereg_version: 1",
        "id: " + id,
        "title: test " + treatment,
        "hypothesis: test",
        "metric: test",
        "baseline: baseline",
        "treatments: [" + treatment + "]",
        "period: {start: 2026-03-02, end: 2026-03-03}",
        "fitting_period: {start: 2026-02-16, end: 2026-02-27}",
        "holdout: [2026-04-06, 2026-04-07]",
        "success_threshold: test",
        "assumption_band:",
        "  preset: usd_rfq_hit_rate",
        "  parameter: spread_to_cover_bp",
        "  points: [low, central, high]",
        "author: test",
        "date: 2026-10-31",
        "config: data/experiments/R2B.config.yaml",
        "config_sha256: " + configSha,
        "limitations: test",
        "");
  }

  @Test
  @DisplayName("cli: refuses without valid, consistent pre-registrations; otherwise records one row each")
  void cliRunsAndRecords() throws IOException {
    final Path repo = dir.resolve("repo");
    Files.createDirectories(repo.resolve("data/experiments"));
    Files.createDirectories(repo.resolve("data/preregs"));
    Files.copy(Support.repoRoot().resolve("data/presets.yaml"), repo.resolve("data/presets.yaml"));
    final Path config = repo.resolve(Support.CONFIG);
    Files.copy(Support.repoRoot().resolve(Support.CONFIG), config);
    final String sha = Hashing.sha256TextFile(config);
    final StringBuilder list = new StringBuilder();
    for (int i = 0; i < DeskFactory.TREATMENTS.size(); i++) {
      final String id = "T-00" + (i + 1);
      Files.writeString(
          repo.resolve("data/preregs/" + id + ".yaml"), prereg(id, DeskFactory.TREATMENTS.get(i), sha),
          StandardCharsets.UTF_8);
      list.append(i == 0 ? "" : ",").append("data/preregs/").append(id).append(".yaml");
    }
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    assertEquals(DeskExperimentCli.REFUSED, DeskExperimentCli.run(new String[] {}, out));
    assertEquals(
        DeskExperimentCli.REFUSED,
        DeskExperimentCli.run(new String[] {"--repo", repo.toString(), "--preregs", "data/preregs/none.yaml"}, out));
    assertEquals(
        DeskExperimentCli.REFUSED,
        DeskExperimentCli.run(
            new String[] {"--repo", repo.toString(), "--preregs", list.toString(), "--scope", "later"}, out));
    Files.writeString(
        repo.resolve("data/preregs/bad.yaml"), prereg("B-1", "sizing", sha).replace("baseline: baseline", "baseline: x"),
        StandardCharsets.UTF_8);
    assertEquals(
        DeskExperimentCli.REFUSED,
        DeskExperimentCli.run(
            new String[] {"--repo", repo.toString(), "--preregs", "data/preregs/T-001.yaml,data/preregs/bad.yaml"}, out));
    assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("baseline must be baseline"));

    final int code =
        DeskExperimentCli.run(
            new String[] {
              "--repo", repo.toString(), "--preregs", list.toString(), "--work", dir.resolve("work").toString(),
              "--commit", "test"
            },
            out);
    assertEquals(DeskExperimentCli.OK, code);
    final List<Map<String, Object>> entries = new Ledger(repo.resolve("data/ledger/ledger.jsonl")).entries();
    assertEquals(7, entries.size());
    for (final Map<String, Object> e : entries) {
      assertTrue(List.of("supported", "not supported").contains(e.get("verdict")), e.get("verdict").toString());
      @SuppressWarnings("unchecked")
      final Map<String, Object> results = (Map<String, Object>) e.get("results");
      @SuppressWarnings("unchecked")
      final Map<String, Object> band = (Map<String, Object>) results.get("band");
      assertEquals(java.util.Set.of("low", "central", "high"), band.keySet());
      assertTrue(results.containsKey("adopted_as_default"));
      assertEquals(6, ((List<?>) e.get("journals")).size());
    }
    assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("recorded in data/ledger/ledger.jsonl"));
    assertThrows(IllegalArgumentException.class, () -> DeskExperimentCli.parse(new String[] {"--x"}));
  }
}
