package io.ratesalgo.strategy.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.strategy.Support;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The carry-limit harness (V1C-001) and the latency sweep on one or two short days. */
class CarryLimitExperimentTest {

  private static final String CONFIG = "data/experiments/V1C.config.yaml";

  @TempDir Path dir;

  /** A copy of V1C-001 whose fitting period is one day, so the command line runs quickly. */
  private Path oneDayPrereg() throws IOException {
    final Path p = dir.resolve("V1C-one-day.yaml");
    Files.writeString(
        p,
        Files.readString(Support.repoRoot().resolve("data/preregs/V1C-001.yaml"))
            .replace("fitting_period: {start: 2026-04-13, end: 2026-04-24}", "fitting_period: {start: 2026-04-13, end: 2026-04-13}"));
    return p;
  }

  private static String run(final String... args) {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CarryLimitExperiment.run(args, new PrintStream(bytes, true, StandardCharsets.UTF_8));
    return bytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("harness: the carry limit shortens carry and its evaluation has every field")
  void evaluates() {
    final CarryLimitExperiment e = new CarryLimitExperiment(Support.repoRoot(), Support.repoRoot().resolve(CONFIG));
    final CarryLimitExperiment.Params p = e.configured();
    assertEquals(new CarryLimitExperiment.Params(50_000, 0, 900_000_000_000L), p, "the pre-registered parameters");
    assertEquals("floor 50000 max 900s", p.label());
    final List<LocalDate> days = List.of(LocalDate.of(2026, 4, 13), LocalDate.of(2026, 4, 14));
    final List<Map<String, Object>> journals = new ArrayList<>();
    final HedgeExperiment.Session[][][] m = e.run(days, List.of(p), dir.resolve("work"), journals);
    assertEquals(6, journals.size());
    final Map<String, Object> res = e.evaluate(m, days, p);
    assertEquals(CarryLimitExperiment.TREATMENT, res.get("treatment"));
    assertTrue(List.of("supported", "not supported").contains(res.get("verdict")));
    @SuppressWarnings("unchecked")
    final Map<String, Object> high =
        (Map<String, Object>) ((Map<String, Object>) res.get("band")).get(BandPoint.HIGH.label());
    @SuppressWarnings("unchecked")
    final Map<String, Map<String, Double>> sec = (Map<String, Map<String, Double>>) high.get("secondary_means");
    assertTrue(sec.get("carry_p50_s").get("treatment") < sec.get("carry_p50_s").get("baseline"), "shorter carry at high");
    assertTrue(sec.get("hedges").get("treatment") >= sec.get("hedges").get("baseline"), "at least as many hedges");
  }

  @Test
  @DisplayName("command line: fitting prints a table per band point; refusals run nothing")
  void commandLine() throws IOException {
    final String repo = Support.repoRoot().toString();
    final String out =
        run("--prereg", oneDayPrereg().toString(), "--repo", repo, "--scope", "fitting", "--grid", "50000:900",
            "--work", dir.resolve("cli").toString());
    assertTrue(out.contains("floor 50000 max 900s"), out);
    assertTrue(out.contains("central") && out.contains("high"), out);
    assertTrue(run("--repo", repo).contains("--prereg is required"));
    assertTrue(run("--prereg", "x.yaml", "--repo", repo, "--scope", "later").contains("--scope must be"));
    final Path bad = dir.resolve("V1C-bad-hash.yaml");
    Files.writeString(bad, Files.readString(oneDayPrereg()).replaceFirst("config_sha256: [0-9a-f]+", "config_sha256: 00"));
    assertTrue(
        run("--prereg", bad.toString(), "--repo", repo, "--ledger", dir.resolve("ledger.jsonl").toString())
            .contains("does not match the config file"),
        "a pre-registration whose config hash does not match is refused before any data is read");
  }

  @Test
  @DisplayName("latency sweep: one day, one latency, written as JSON")
  void latencySweep() throws IOException {
    final Path out = dir.resolve("sweep.json");
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final Map<String, Object> r =
        LatencySweep.run(
            new String[] {"--prereg", oneDayPrereg().toString(), "--repo", Support.repoRoot().toString(),
              "--latencies-ms", "2", "--out", out.toString(), "--work", dir.resolve("sweep").toString()},
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
    assertTrue(Files.readString(out).contains("carry_p50_s"));
    assertEquals(List.of(2.0), r.get("latencies_ms"));
    assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("desk_default"));
  }
}
