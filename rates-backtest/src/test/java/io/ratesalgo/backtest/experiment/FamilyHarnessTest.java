package io.ratesalgo.backtest.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.TestSupport;
import io.ratesalgo.backtest.prereg.DataSets;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.prereg.LedgerCli;
import io.ratesalgo.backtest.prereg.PreRegistration;
import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimParams;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A registered family (research/prereg/R5-FAM-001.yaml) through the harness: its rows run their
 * own arms on their own sessions, a session resumes at the next arm, a timing-only pilot writes no
 * P&amp;L, the summary is computed once with one ledger row per hypothesis carrying the family id,
 * the run is one look, and the Benjamini-Hochberg table covers the family. The arms are toys; no
 * desk and no P&amp;L level is examined.
 */
class FamilyHarnessTest {

  /** The toy arms under the family's names: default, v2 and x. */
  public static final class FamilyToyArms implements ArmFactory {
    static final AtomicInteger CREATED = new AtomicInteger();

    static String toy(final String arm) {
      return switch (arm) {
        case "default" -> "fixed";
        case "v2" -> "fixed_hedged";
        case "x" -> "idle";
        default -> arm;
      };
    }

    @Override
    public String refusal(final String arm) {
      return new ToyArms().refusal(toy(arm));
    }

    @Override
    public Strategy create(final String arm, final SimDay day) {
      CREATED.incrementAndGet();
      return new ToyArms().create(toy(arm), day);
    }

    @Override
    public String configurationHash(final String arm) {
      return Hashing.sha256Text(toy(arm));
    }
  }

  private static final long BASE = 31_000_000L;
  private static final long HOUR = 3_600_000_000_000L;

  /** A family pre-registration on {@code base} with rows central and winner_half_spread:low. */
  static String family(final Path repo, final String id, final String status, final long base) throws IOException {
    final Path config = repo.resolve("research/prereg/" + id + ".config.yaml");
    Files.writeString(
        config,
        "arms: {default: a, v2: b, x: c}\n"
            + "seeds: {pilot_base: 29000000}\n"
            + "statistics: {block_sessions: 21, resamples: 200, bootstrap_seed: 5}\n"
            + "smallest_effect: {usd_per_session: 1000}\n"
            + "family: {id: " + id + ", q: 0.05, guards_vs_default: {client_dv01: 0.25},"
            + " structural_declines: not_above_baseline, hypotheses: ["
            + "{id: H1, treatment: v2, baseline: default, guards: {quoted_share: 0.8}},"
            + " {id: H2, treatment: v2, baseline: x}]}\n"
            + "rows: [{row: central, arms: [default, v2, x], sessions: 2000},"
            + " {row: 'winner_half_spread:low', arms: [default, v2], sessions: 1000}]\n"
            + "excluded_band_points: [{row: 'winner_half_spread:high', reason: refused at start-up}]\n",
        StandardCharsets.UTF_8);
    final String text =
        "prereg_version: 2\n" + status + "id: " + id + "\ntitle: t\nhypothesis: h\nmetric: m\nbaseline: default\n"
            + "treatments: [v2, x]\ntest: paired t\ndata_set:\n  seed_base: " + base + "\n  sessions: 2000\n"
            + "  simulator_params: data/calibration/sim_v2_params.json\n  simulator_params_sha256: "
            + Hashing.sha256TextFile(repo.resolve("data/calibration/sim_v2_params.json")) + "\n"
            + "success_threshold: s\nassumption_band: {preset: sim_v2_params, parameters: [winner_half_spread],"
            + " points: [low, central, high]}\nprior_looks: []\nauthor: a\ndate: 2026-11-23\n"
            + "config: research/prereg/" + id + ".config.yaml\nconfig_sha256: " + Hashing.sha256TextFile(config) + "\n";
    Files.writeString(repo.resolve("research/prereg/" + id + ".yaml"), text, StandardCharsets.UTF_8);
    return "research/prereg/" + id + ".yaml";
  }

  private static Map<String, Object> arm(final double pnl, final long quoted, final long structural) {
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
    m.put("structural_declines", structural);
    return m;
  }

  /** A finished session record of the family above: deterministic numbers, no run. */
  private static Map<String, Object> session(final long seed) {
    final int n = (int) (seed % SessionSeeds.SPAN);
    final double base = 100.0 * Math.sin(n);
    final Map<String, Object> central = new LinkedHashMap<>();
    central.put("default", arm(base, 10, 3));
    central.put("v2", arm(base + 50.0 + 10.0 * Math.cos(n), 10, 0));
    central.put("x", arm(base + 50.0 + 5.0 * Math.cos(3.0 * n), 10, 0));
    final Map<String, Object> rows = new LinkedHashMap<>();
    rows.put("central", Map.of("journal_sha256", "j" + seed, "journal_chain", "c" + seed, "arms", central));
    if (n < 1000) {
      final Map<String, Object> low = new LinkedHashMap<>();
      low.put("default", arm(base, 10, 3));
      low.put("v2", arm(base - 1.0 - Math.cos(n), 10, 0));
      rows.put("winner_half_spread:low", Map.of("journal_sha256", "k" + seed, "arms", low));
    }
    final Map<String, Object> s = new LinkedHashMap<>();
    s.put("seed", seed);
    s.put("day_type", SessionSeeds.dayType(seed).name());
    s.put("rows", rows);
    s.put("wall_ms", 1000L);
    return s;
  }

  @Test
  void aFamilyRunRecordsOneRowPerHypothesisAsOneLookAndPrintsItsTable(@TempDir final Path dir) throws IOException {
    final Path repo = HarnessFixtures.repo(dir);
    final String prereg = family(repo, "F-1", "", BASE);
    final Path out = dir.resolve("out");
    final String ledger = dir.resolve("ledger.jsonl").toString();
    // the 2,000 sessions are in place, as a finished run leaves them: the run goes to its summary
    final Checkpoints cp = new Checkpoints(out);
    cp.prepare();
    for (int n = 0; n < 2000; n++) {
      cp.write(BASE + n, session(BASE + n));
    }
    final String[] args = {
      "run", "--prereg", prereg, "--repo", repo.toString(), "--out", out.toString(), "--factory",
      FamilyToyArms.class.getName(), "--ledger", ledger, "--in-process", "--commit", "abc123", "--dirty", "false"
    };
    final HarnessFixtures.Run r = HarnessFixtures.run(args);
    assertEquals(ExperimentRunner.OK, r.code(), r.out());
    assertTrue(r.out().contains("family F-1, 2 hypotheses, 2 rows, 8000 arm-sessions in all"), r.out());
    assertTrue(r.out().contains("3 rows recorded"), r.out());
    // the table LedgerCli --bh family:F-1 prints is printed by the run
    assertTrue(r.out().contains("family family:F-1 (q = 0.05): 2 tests from 2 ledger rows"), r.out());
    assertTrue(r.out().contains("  F-1 H1 v2_vs_default  seq 1"), r.out());
    assertTrue(r.out().contains("  F-1 H2 v2_vs_x  seq 2"), r.out());

    final List<Map<String, Object>> rows = new Ledger(Path.of(ledger)).entries();
    assertEquals(3, rows.size());
    final Object run = rows.get(0).get("family_run");
    for (final Map<String, Object> row : rows) {
      assertEquals("F-1", row.get("family_id"));
      assertEquals(run, row.get("family_run"));
      assertEquals(1L, ((Number) row.get("data_set_look")).longValue());
      assertEquals(1L, ((Number) row.get("look")).longValue());
      assertEquals(DataSets.sessionsKey(BASE, 0, 2000), row.get("data_set"));
      assertEquals("confirmation", row.get("scope"));
    }
    assertEquals(List.of("H1", "H2", "D0"), rows.stream().map(e -> e.get("hypothesis_id")).toList());
    assertEquals("description", rows.get(2).get("verdict"));
    final Map<String, Object> h1 = Yaml.asMap(rows.get(0).get("results"), "results");
    final Map<String, Object> c = Yaml.asMap(Yaml.asMap(h1.get("primary"), "primary").get("v2_vs_default"), "c");
    assertEquals(2000L, ((Number) c.get("n")).longValue());
    final Map<String, Object> guards = Yaml.asMap(c.get("guards"), "guards");
    assertEquals(1.0, ((Number) guards.get("client_dv01_vs_default_ratio")).doubleValue(), 0.0);
    assertEquals(1.0, ((Number) guards.get("structural_declines_not_above_baseline_ratio")).doubleValue(), 0.0);
    assertNotNull(c.get("minimum_detectable_difference_80"));
    // the sensitivity row is description: a sign and an interval, no p-value
    final Map<String, Object> low =
        Yaml.asMap(Yaml.asMap(h1.get("band_secondary"), "secondary").get("winner_half_spread:low"), "low");
    assertEquals(-1L, ((Number) low.get("sign")).longValue());
    assertEquals(1000L, ((Number) low.get("n")).longValue());
    assertFalse(low.containsKey("paired_p"));
    assertNull(Yaml.asMap(rows.get(1).get("results"), "r2").get("band_secondary"), "v2 against x is not in the band row");
    final Map<String, Object> min = Yaml.asMap(rows.get(0).get("guards_min"), "min");
    assertEquals(Map.of("quoted_share", 0.8, "client_dv01_vs_default", 0.25, "structural_declines_not_above_baseline", 1.0),
        min.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
            e -> ((Number) e.getValue()).doubleValue())));
    assertTrue(Files.isRegularFile(cp.summaryFile()));

    // one look at the data set, said so by the looks report
    final String looks = DataSets.report(rows);
    assertTrue(looks.contains(": 1 look\n"), looks);
    assertTrue(looks.contains("  look 1  seq 1..3  F-1  confirmation  family F-1, 3 rows  (recorded as look 1)"), looks);
    assertFalse(looks.contains("warning"), looks);
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    assertEquals(0, LedgerCli.run(new String[] {"--ledger", ledger, "--bh", "family:F-1"},
        new PrintStream(bytes, true, StandardCharsets.UTF_8)));
    assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("F-1 H1 v2_vs_default"), bytes.toString(StandardCharsets.UTF_8));

    // a family reads its data set once
    final HarnessFixtures.Run again = HarnessFixtures.run(args);
    assertEquals(ExperimentRunner.REFUSED, again.code());
    assertTrue(again.out().contains("has already read data set"), again.out());
  }

  @Test
  void theRegisteredFamilyValidatesAndItsExcludedPointsAreRefusedByTheSimulator() {
    final Path root = TestSupport.repoRoot();
    final HarnessFixtures.Run v =
        HarnessFixtures.run("validate", "--prereg", "research/prereg/R5-FAM-001.yaml", "--repo", root.toString());
    assertEquals(ExperimentRunner.OK, v.code(), v.out());
    assertTrue(v.out().contains("R5-FAM-001: valid (draft: validates, is not run)"), v.out());
    final PreRegistration p = PreRegistration.load(root.resolve("research/prereg/R5-FAM-001.yaml"));
    final SessionDesign d = SessionDesign.of(p, root);
    assertEquals(31_000_000L, d.seedBase());
    assertEquals(2000, d.sessions());
    assertEquals(9, d.arms().size());
    assertEquals("R5-FAM-001", d.plan().familyId());
    assertEquals(8, d.plan().hypotheses().size());
    assertEquals(22_000L, d.plan().armSessions());
    assertEquals(4_000L, d.plan().journals());
    assertEquals(List.of("central", "all_in_cost:low", "winner_half_spread:low"),
        d.plan().rows().stream().map(SessionDesign.RowPlan::label).toList());
    assertEquals(List.of("default", "v2"), d.row("all_in_cost:low").arms());
    assertEquals(1000, d.row("winner_half_spread:low").sessions());
    assertNull(d.row("impact:low"));
    // both excluded points are what the pre-registration says: refused at start-up
    assertEquals(List.of("all_in_cost:high", "winner_half_spread:high"), List.copyOf(d.plan().excluded().keySet()));
    assertEquals(List.of(), ExperimentRunner.exclusionRefusals(d));
    assertEquals(3, ExperimentRunner.rows(d).size());
    final SessionDesign.Hypothesis h1 = d.plan().hypotheses().get(0);
    assertEquals("v2_vs_default", h1.comparison());
    assertEquals(0.25, h1.guards().get("hit_rate"), 0.0);
    assertTrue(d.plan().structuralGuard());
    assertEquals(0.25, d.plan().guardsVsDefault().get("client_dv01"), 0.0);
    assertNull(ExperimentRunner.supersededRefusal(p, d));
    // and it is unsigned: the runner refuses it
    final HarnessFixtures.Run run =
        HarnessFixtures.run("run", "--prereg", "research/prereg/R5-FAM-001.yaml", "--repo", root.toString(), "--out",
            Path.of(System.getProperty("java.io.tmpdir"), "never-written").toString(), "--factory",
            FamilyToyArms.class.getName(), "--dirty", "false");
    assertEquals(ExperimentRunner.REFUSED, run.code());
    assertTrue(run.out().contains("R5-FAM-001 is a draft"), run.out());
  }

  @Test
  void aSupersededDraftIsNeverRunNorPiloted(@TempDir final Path dir) {
    final Path root = TestSupport.repoRoot();
    for (final String cmd : List.of("run", "pilot")) {
      final HarnessFixtures.Run r =
          HarnessFixtures.run(cmd, "--prereg", "research/prereg/R5-007.yaml", "--repo", root.toString(), "--out",
              dir.resolve(cmd).toString(), "--factory", ToyArms.class.getName(), "--pilot", "2");
      assertEquals(ExperimentRunner.REFUSED, r.code(), r.out());
      assertTrue(r.out().contains("R5-007 was superseded before signing by R5-FAM-001 (2026-11-23)"), r.out());
    }
  }

  @Test
  void anExcludedPointThatStartsIsRefused(@TempDir final Path dir) throws IOException {
    final Path repo = HarnessFixtures.repo(dir);
    final String prereg = family(repo, "F-2", "", BASE);
    // winner_half_spread:low starts: excluding it would leave out a row that could run
    final Path config = repo.resolve("research/prereg/F-2.config.yaml");
    final String text = Files.readString(config, StandardCharsets.UTF_8)
        .replace("{row: 'winner_half_spread:low', arms: [default, v2], sessions: 1000}",
            "{row: 'winner_half_spread:high', arms: [default, v2], sessions: 1000}")
        .replace("{row: 'winner_half_spread:high', reason: refused at start-up}", "{row: 'winner_half_spread:low', reason: x}");
    Files.writeString(config, text, StandardCharsets.UTF_8);
    final Path yaml = repo.resolve(prereg);
    Files.writeString(yaml, Files.readString(yaml, StandardCharsets.UTF_8).replaceAll(
        "config_sha256: [0-9a-f]{64}", "config_sha256: " + Hashing.sha256TextFile(config)), StandardCharsets.UTF_8);
    final PreRegistration p = PreRegistration.load(yaml);
    assertEquals(List.of(), p.validate(repo));
    final SessionDesign d = SessionDesign.of(p, repo);
    // the high row is refused when the rows are built, and the low exclusion is refused by the runner
    assertThrows(IllegalArgumentException.class, () -> ExperimentRunner.rows(d));
    assertEquals(1, ExperimentRunner.exclusionRefusals(d).size());
    assertTrue(ExperimentRunner.exclusionRefusals(d).get(0).startsWith("excluded band point winner_half_spread:low starts"));
    final HarnessFixtures.Run r =
        HarnessFixtures.run("run", "--prereg", prereg, "--repo", repo.toString(), "--out", dir.resolve("o").toString(),
            "--factory", FamilyToyArms.class.getName(), "--dirty", "false");
    assertEquals(ExperimentRunner.REFUSED, r.code());
    assertTrue(r.out().contains("winner_half_spread:high is refused by the simulator"), r.out());
  }

  @Test
  void theWorkerRunsEachRowOnItsOwnSessions() {
    final Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("session_seeds", Map.of("base", BASE, "first", 0L, "count", 2000L));
    manifest.put("row_plans", List.of(
        Map.of("row", "central", "arms", List.of("default", "v2", "x"), "sessions", 2000L),
        Map.of("row", "winner_half_spread:low", "arms", List.of("default", "v2"), "sessions", 1000L)));
    final List<SessionExecutor.RowRun> early = SessionWorker.runs(manifest, BASE + 999);
    assertEquals(2, early.size());
    assertEquals(List.of("default", "v2"), early.get(1).arms());
    assertEquals("winner_half_spread:low", early.get(1).row().label());
    final List<SessionExecutor.RowRun> late = SessionWorker.runs(manifest, BASE + 1000);
    assertEquals(1, late.size());
    assertEquals(List.of("default", "v2", "x"), late.get(0).arms());
    // a single pre-registration's manifest: every arm on every row
    final Map<String, Object> single = Map.of("arms", List.of("a", "b"), "rows", List.of("central", "impact:low"));
    final List<SessionExecutor.RowRun> all = SessionWorker.runs(single, BASE);
    assertEquals(2, all.size());
    assertEquals(List.of("a", "b"), all.get(1).arms());
    assertTrue(new SessionDesign.RowPlan("central", List.of("a"), -1).includes(5_000));
  }

  @Test
  void aSessionResumesAtTheNextArmAndAJournalThatChangedFailsIt(@TempDir final Path dir) {
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 62_300);
    final SessionExecutor executor = new SessionExecutor(TestSupport.presets(), dir.resolve("work"));
    final List<SessionExecutor.RowRun> runs =
        List.of(new SessionExecutor.RowRun(new BandParameters.Row(BandParameters.CENTRAL, SimParams.loadDefault()),
            List.of("default", "v2", "x")));
    final Checkpoints cp = new Checkpoints(dir.resolve("out"));
    cp.prepare();
    final SessionExecutor.Parts parts = cp.parts(seed);
    FamilyToyArms.CREATED.set(0);
    final Map<String, Object> whole = executor.run(seed, runs, new FamilyToyArms(), parts, false, HOUR / 4);
    assertEquals(3, FamilyToyArms.CREATED.get());
    assertEquals(3L, cp.partCount(seed));
    // all parts present: nothing is run again, the record is assembled from them
    FamilyToyArms.CREATED.set(0);
    final Map<String, Object> resumed = executor.run(seed, runs, new FamilyToyArms(), cp.parts(seed), false, HOUR / 4);
    assertEquals(0, FamilyToyArms.CREATED.get());
    assertEquals(Json.write(Yaml.asMap(whole.get("rows"), "r")), Json.write(Yaml.asMap(resumed.get("rows"), "r")));
    // a worker that died after the first arm: only the other two run, on a journal that hashes the same
    final SessionExecutor.Parts partial = SessionExecutor.inMemory();
    final Map<String, Object> row = Yaml.asMap(Yaml.asMap(whole.get("rows"), "rows").get("central"), "central");
    final Map<String, Object> rowFacts = new LinkedHashMap<>(row);
    rowFacts.remove("arms");
    partial.putRow("central", rowFacts);
    partial.putArm("central", "default", Yaml.asMap(Yaml.asMap(row.get("arms"), "arms").get("default"), "d"));
    FamilyToyArms.CREATED.set(0);
    final Map<String, Object> rest = executor.run(seed, runs, new FamilyToyArms(), partial, false, HOUR / 4);
    assertEquals(2, FamilyToyArms.CREATED.get());
    final Map<String, Object> restArms =
        Yaml.asMap(Yaml.asMap(Yaml.asMap(rest.get("rows"), "rows").get("central"), "c").get("arms"), "a");
    for (final String arm : List.of("default", "v2", "x")) {
      final Map<String, Object> a = new LinkedHashMap<>(Yaml.asMap(restArms.get(arm), arm));
      final Map<String, Object> b = new LinkedHashMap<>(Yaml.asMap(Yaml.asMap(row.get("arms"), "arms").get(arm), arm));
      a.remove("wall_ms");
      b.remove("wall_ms");
      assertEquals(Json.write(b), Json.write(a), arm);
      assertEquals(row.get("journal_chain"), a.get("input_chain"), "common random numbers: " + arm);
      assertEquals(Hashing.sha256Text(FamilyToyArms.toy(arm)), a.get("config_hash"));
    }
    // the journal written again must hash as it did: a session that is not deterministic fails
    final SessionExecutor.Parts bad = SessionExecutor.inMemory();
    final Map<String, Object> wrong = new LinkedHashMap<>(rowFacts);
    wrong.put("journal_sha256", "0".repeat(64));
    bad.putRow("central", wrong);
    final IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> executor.run(seed, runs, new FamilyToyArms(), bad, false, HOUR / 4));
    assertTrue(e.getMessage().contains("not deterministic"), e.getMessage());
    cp.clearParts(seed);
    assertEquals(0L, cp.partCount(seed));
    cp.clearParts(seed);
  }

  @Test
  void aTimingOnlyPilotWritesAndPrintsNoPnl(@TempDir final Path dir) throws IOException {
    final Path repo = HarnessFixtures.repo(dir);
    final String prereg = family(repo, "F-3", "status: draft_unsigned\n", BASE);
    final Path out = dir.resolve("pilot");
    final HarnessFixtures.Run r =
        HarnessFixtures.run(
            "pilot", "--prereg", prereg, "--repo", repo.toString(), "--out", out.toString(), "--factory",
            FamilyToyArms.class.getName(), "--pilot", "2", "--timing-only", "--in-process", "--workers", "8");
    assertEquals(ExperimentRunner.OK, r.code(), r.out());
    assertTrue(r.out().contains("arms [default, v2, x]"), r.out());
    assertTrue(r.out().contains("timing only: no P&L is written or read"), r.out());
    assertTrue(r.out().contains("config_hashes_distinct: true"), r.out());
    assertTrue(r.out().contains("inputs_identical_across_arms: true"), r.out());
    assertTrue(r.out().contains("\"arm_sessions\":8000"), r.out());
    assertTrue(r.out().contains("\"hours_at_25_s_per_arm_session\":6.9"), r.out());
    for (final String forbidden : List.of("position_pnl", "mean_pnl", "spread_capture", "mean_difference", "difference_sd", "session_sd")) {
      assertFalse(r.out().contains(forbidden), forbidden + " in " + r.out());
    }
    // nothing on disk carries a P&L line either
    final List<String> texts = new ArrayList<>();
    try (var files = Files.walk(out)) {
      for (final Path f : files.filter(Files::isRegularFile).toList()) {
        texts.add(Files.readString(f, StandardCharsets.UTF_8));
      }
    }
    for (final String t : texts) {
      assertFalse(t.contains("position_pnl") || t.contains("spread_capture") || t.contains("output_chain"), t);
    }
    final Map<String, Object> manifest = new Checkpoints(out).readManifest();
    assertEquals("pilot_timing_only", manifest.get("kind"));
    assertEquals(true, manifest.get("timing_only"));
    final Map<String, Object> report = Yaml.loadMap(out.resolve("pilot.json"));
    final Map<String, Object> v2 = Yaml.asMap(Yaml.asMap(report.get("arms"), "arms").get("v2"), "v2");
    assertTrue(((Number) v2.get("seconds_per_session")).doubleValue() >= 0.0);
    assertTrue(Yaml.asMap(v2.get("summed_over_sessions"), "s").containsKey("hedge_fills"));
  }

  @Test
  void theFamilySummaryJudgesGuardsAgainstTheBaselineAndTheDefaultArm() {
    final SessionDesign.Plan plan =
        new SessionDesign.Plan(
            List.of(new SessionDesign.RowPlan("central", List.of("default", "v2", "x"), 2000)),
            List.of(new SessionDesign.Hypothesis("H1", "x", "default", Map.of("client_dv01", 0.5))),
            "F", 0.05, Map.of("client_dv01", 0.25), true, Map.of(), null);
    final SessionDesign design =
        new SessionDesign("F", BASE, 0, 4, "default", List.of("v2", "x"), List.of(), 2, 200, 5L, 1000.0, 29_000_000L, false,
            Map.of(), plan);
    final Map<Long, Map<String, Object>> sessions = new LinkedHashMap<>();
    for (int n = 0; n < 4; n++) {
      final Map<String, Object> s = session(BASE + 1000 + n);
      // x declines more for a structural reason than the default arm
      Yaml.asMap(Yaml.asMap(Yaml.asMap(s.get("rows"), "rows").get("central"), "c").get("arms"), "a")
          .put("x", arm(n, 10, 9));
      sessions.put(BASE + 1000 + n, s);
    }
    final Map<String, Object> r = ExperimentSummary.computeFamily(design, sessions);
    final Map<String, Object> c = Yaml.asMap(Yaml.asMap(r.get("hypotheses"), "h").get("H1"), "H1");
    final Map<String, Object> g = Yaml.asMap(c.get("guards"), "g");
    assertEquals(0.0, ((Number) g.get("structural_declines_not_above_baseline_ratio")).doubleValue(), 0.0);
    assertEquals(9L * 4, ((Number) g.get("treatment_structural_declines")).longValue());
    assertEquals(Map.of("client_dv01", 0.5, "client_dv01_vs_default", 0.25, "structural_declines_not_above_baseline", 1.0),
        ExperimentSummary.guardMinimums(plan.hypotheses().get(0), plan));
    // delta_80 = (z_0.975 + z_0.8) sd / sqrt(n), and at a = 0.05 / m for one hypothesis the same
    final double sd = ((Number) c.get("sd_difference")).doubleValue();
    final Map<String, Object> mde = Yaml.asMap(c.get("minimum_detectable_difference_80"), "mde");
    assertEquals(ExperimentSummary.round((1.959964 + 0.841621) * sd / 2.0, 2), ((Number) mde.get("at_a_0_05")).doubleValue(), 0.011);
    assertEquals(mde.get("at_a_0_05"), mde.get("at_a_0_05_over_m"));
    assertEquals(Map.of(), r.get("band_secondary"));
    assertNotNull(r.get("descriptive_baseline"));
  }
}
