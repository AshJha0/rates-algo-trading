package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.prereg.ExperimentCli;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.prereg.PreRegistration;
import io.ratesalgo.backtest.prereg.PreRegistrationException;
import io.ratesalgo.backtest.util.Hashing;
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

/**
 * The ledger refuses an unregistered run and a holdout overlap (P5 gate), keeps its hash chain,
 * and records a registered run whatever its verdict.
 */
class LedgerTest {

  @TempDir Path repo;

  private static final String CONFIG =
      """
      experiment: T
      presets: data/presets.yaml
      day_seed_base: 5000000
      outcome_seed: 7
      strategies:
        fixed_spread: {swap_half_spread_bp: 0.30, bond_half_spread_bp: 0.15, hedge_threshold_dv01: 100000}
        model_spread: {adverse_bp_by_tier: [0.02, 0.05, 0.12], swap_hedge_cost_bp: 0.15, bond_hedge_cost_bp: 0.15, \
      max_half_spread_bp: 2.0, grid_step_bp: 0.01, hedge_threshold_dv01: 100000}
      statistics: {bootstrap_block_days: 1, bootstrap_resamples: 200, bootstrap_seed: 11, level: 0.95, alpha: 0.05}
      """;

  private void setUp() throws IOException {
    Files.createDirectories(repo.resolve("data/experiments"));
    Files.createDirectories(repo.resolve("data/preregs"));
    Files.copy(TestSupport.repoRoot().resolve("data/presets.yaml"), repo.resolve("data/presets.yaml"));
    Files.writeString(repo.resolve("data/experiments/t.yaml"), CONFIG, StandardCharsets.UTF_8);
  }

  private Path prereg(final String holdout, final String sha) throws IOException {
    final String text =
        "prereg_version: 1\nid: T-001\ntitle: test\nhypothesis: h\nmetric: m\nbaseline: fixed_spread\n"
            + "treatments: [model_spread]\nperiod: {start: 2026-02-02, end: 2026-02-03}\n"
            + "fitting_period: {start: 2026-01-05, end: 2026-01-16}\nholdout: [" + holdout + "]\n"
            + "success_threshold: s\nassumption_band: {preset: usd_rfq_hit_rate, parameter: spread_to_cover_bp, points: [low, central, high]}\n"
            + "author: a\ndate: 2026-10-31\nconfig: data/experiments/t.yaml\nconfig_sha256: " + sha + "\n";
    final Path p = repo.resolve("data/preregs/t.yaml");
    Files.writeString(p, text, StandardCharsets.UTF_8);
    return p;
  }

  private int cli(final String... args) {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      final String[] all = new String[args.length + 4];
      System.arraycopy(args, 0, all, 0, args.length);
      all[args.length] = "--repo";
      all[args.length + 1] = repo.toString();
      all[args.length + 2] = "--work";
      all[args.length + 3] = repo.resolve("work").toString();
      return ExperimentCli.run(all, out);
    }
  }

  private String sha() {
    return Hashing.sha256TextFile(repo.resolve("data/experiments/t.yaml"));
  }

  @Test
  @DisplayName("ledger refuses a run without a pre-registration")
  void refusesUnregisteredRun() throws IOException {
    setUp();
    assertEquals(ExperimentCli.REFUSED, cli("--commit", "x"));
    assertEquals(ExperimentCli.REFUSED, cli("--prereg", "data/preregs/missing.yaml"));
    assertFalse(Files.exists(repo.resolve("data/ledger/ledger.jsonl")));
  }

  @Test
  @DisplayName("ledger refuses a holdout that overlaps the fitting period")
  void refusesHoldoutOverlap() throws IOException {
    setUp();
    prereg("2026-01-09, 2026-02-20", sha());
    assertEquals(ExperimentCli.REFUSED, cli("--prereg", "data/preregs/t.yaml"));
    final List<String> problems = PreRegistration.load(repo.resolve("data/preregs/t.yaml")).validate(repo);
    assertTrue(problems.stream().anyMatch(p -> p.contains("overlaps the fitting period")), problems.toString());
    prereg("2026-02-03", sha());
    assertTrue(
        PreRegistration.load(repo.resolve("data/preregs/t.yaml")).validate(repo).stream()
            .anyMatch(p -> p.contains("inside the evaluation period")));
    assertFalse(Files.exists(repo.resolve("data/ledger/ledger.jsonl")));
  }

  @Test
  void refusesConfigHashMismatchAndIncompleteFiles() throws IOException {
    setUp();
    prereg("2026-02-20", "0".repeat(64));
    assertEquals(ExperimentCli.REFUSED, cli("--prereg", "data/preregs/t.yaml"));
    Files.writeString(repo.resolve("data/preregs/bad.yaml"), "id: X\nperiod: {start: 2026-02-05, end: 2026-02-01}\n");
    final PreRegistrationException e =
        assertThrows(
            PreRegistrationException.class,
            () -> PreRegistration.load(repo.resolve("data/preregs/bad.yaml")).requireValid(repo));
    assertTrue(e.problems().size() > 5);
    Files.writeString(
        repo.resolve("data/preregs/bad2.yaml"),
        Files.readString(prereg("2026-02-20", sha()))
            .replace("[low, central, high]", "[central]")
            .replace("start: 2026-02-02, end: 2026-02-03", "start: 2026-01-10, end: 2026-01-12")
            .replace("prereg_version: 1", "prereg_version: 2"));
    final List<String> p = PreRegistration.load(repo.resolve("data/preregs/bad2.yaml")).validate(repo);
    assertTrue(p.stream().anyMatch(x -> x.contains("DR-008")), p.toString());
    assertTrue(p.stream().anyMatch(x -> x.contains("evaluation period")), p.toString());
    assertTrue(p.stream().anyMatch(x -> x.contains("prereg_version")), p.toString());
    assertEquals(ExperimentCli.REFUSED, cli("--prereg", "data/preregs/t.yaml", "--scope", "everything"));
  }

  @Test
  @DisplayName("a registered run is recorded whatever its verdict, and the holdout opens once")
  void recordsRunAndOpensHoldoutOnce() throws IOException {
    setUp();
    prereg("2026-02-19, 2026-02-20", sha());
    final PreRegistration pr = PreRegistration.load(repo.resolve("data/preregs/t.yaml")).requireValid(repo);
    assertEquals(2, pr.period().businessDays().size());
    assertEquals(ExperimentCli.OK, cli("--prereg", "data/preregs/t.yaml", "--commit", "abc", "--dirty", "true"));
    assertEquals(ExperimentCli.OK, cli("--prereg", "data/preregs/t.yaml", "--scope", "holdout", "--commit", "abc"));
    assertEquals(ExperimentCli.REFUSED, cli("--prereg", "data/preregs/t.yaml", "--scope", "holdout"));
    final Ledger ledger = new Ledger(repo.resolve("data/ledger/ledger.jsonl"));
    final List<Map<String, Object>> entries = ledger.entries();
    assertEquals(2, entries.size());
    assertEquals(pr.sha256(), entries.get(0).get("prereg_sha256"));
    assertTrue(List.of("supported", "not supported").contains(entries.get(0).get("verdict")));
    assertEquals("holdout", entries.get(1).get("scope"));
    assertEquals(1L, ledger.looks("T-001", "holdout"));
    assertTrue(entries.get(0).containsKey("journal_sha256"));

    // tampering with a past line breaks the chain and blocks further appends
    final Path file = ledger.file();
    final String text = Files.readString(file);
    Files.writeString(file, text.replaceFirst("\"verdict\":\"", "\"verdict\":\"edited "));
    assertThrows(IllegalStateException.class, ledger::entries);
    assertThrows(IllegalStateException.class, () -> ledger.append(Map.of("x", 1L)));
  }

  @Test
  void appendStartsTheChainAtGenesis() {
    final Ledger ledger = new Ledger(repo.resolve("l/ledger.jsonl"));
    ledger.append(Map.of("prereg_id", "A", "scope", "evaluation"));
    ledger.append(Map.of("prereg_id", "A", "scope", "evaluation"));
    final List<Map<String, Object>> e = ledger.entries();
    assertEquals(Ledger.GENESIS, e.get(0).get("prev_sha256"));
    assertEquals(2L, ledger.looks("A", "evaluation"));
  }
}
