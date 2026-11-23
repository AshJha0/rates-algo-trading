package io.ratesalgo.backtest.prereg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.TestSupport;
import io.ratesalgo.backtest.experiment.SessionDesign;
import io.ratesalgo.backtest.sim.PairedSessions;
import io.ratesalgo.backtest.util.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Version 2 pre-registrations: the eight drafts validate, every rule refuses what it should with
 * the message {@code ratesresearch.prereg} prints too (the Python test asserts the same strings),
 * data-set keys for session seeds, and the Benjamini-Hochberg family report.
 */
class SessionDesignRulesTest {

  @ParameterizedTest
  @ValueSource(strings = {"SIM3-BASE-001", "R5-001", "R5-002", "R5-003", "R5-004", "R5-005", "R5-006", "R5-007"})
  void theDraftsValidateAndStayDrafts(final String id) {
    final Path repo = TestSupport.repoRoot();
    final PreRegistration p = PreRegistration.load(repo.resolve("research/prereg/" + id + ".yaml"));
    assertEquals(List.of(), p.validate(repo));
    assertEquals(2, p.version());
    assertTrue(p.isDraft());
    final SessionDesign d = SessionDesign.of(p, repo);
    assertEquals(29_000_000L, d.pilotBase());
    assertEquals(21, d.blockSessions());
    assertEquals(2000, d.resamples());
    assertTrue(d.draft());
  }

  @Test
  void theDraftsKeepTheirSeedBasesArmsAndBands() {
    final Path repo = TestSupport.repoRoot();
    final Map<String, Long> bases = new LinkedHashMap<>();
    bases.put("SIM3-BASE-001", 30_000_000L);
    for (int k = 1; k <= 7; k++) {
      bases.put("R5-00" + k, 31_000_000L + k * 1_000_000L);
    }
    for (final Map.Entry<String, Long> e : bases.entrySet()) {
      final SessionDesign d = SessionDesign.of(PreRegistration.load(repo.resolve("research/prereg/" + e.getKey() + ".yaml")), repo);
      assertEquals(e.getValue(), d.seedBase(), e.getKey());
    }
    final SessionDesign base = SessionDesign.of(PreRegistration.load(repo.resolve("research/prereg/SIM3-BASE-001.yaml")), repo);
    assertEquals(2000, base.sessions());
    assertTrue(base.descriptive());
    assertEquals(List.of("engine"), base.arms());
    assertEquals(7, base.bandParameters().size());
    assertEquals(11L, base.bootstrapSeed());
    final SessionDesign r1 = SessionDesign.of(PreRegistration.load(repo.resolve("research/prereg/R5-001.yaml")), repo);
    assertEquals(-1, r1.sessions(), "the count is fixed only after the pilot");
    assertEquals(List.of("default", "cover_pricing"), r1.arms());
    assertEquals(24_000.0, r1.smallestEffectUsd(), 0.0);
    assertEquals(Map.of("quoted_share", 0.8, "hit_rate", 0.25, "client_dv01", 0.25), r1.guardMinimums());
    final SessionDesign r4 = SessionDesign.of(PreRegistration.load(repo.resolve("research/prereg/R5-004.yaml")), repo);
    assertEquals(Map.of("quoted_share", 0.9, "hit_rate", 0.8, "client_dv01", 0.8), r4.guardMinimums());
    assertEquals(List.of("taker_skill", "impact", "impact_half_life", "drift_tau"), r4.bandParameters());
  }

  private static Path repo(final Path dir) throws IOException {
    final Path repo = dir.resolve("repo");
    Files.createDirectories(repo.resolve("data/calibration"));
    Files.copy(TestSupport.repoRoot().resolve("data/calibration/sim_v2_params.json"), repo.resolve("data/calibration/sim_v2_params.json"));
    return repo;
  }

  private static List<String> problems(final Path repo, final String prereg, final String config) throws IOException {
    final Path c = repo.resolve("c.yaml");
    Files.writeString(c, config, StandardCharsets.UTF_8);
    final Path p = repo.resolve("p.yaml");
    Files.writeString(p, prereg.replace("CONFIG_SHA", Hashing.sha256TextFile(c)), StandardCharsets.UTF_8);
    return PreRegistration.load(p).validate(repo);
  }

  static final String GOOD_CONFIG =
      "arms: {a: x, b: y}\nseeds: {pilot_base: 29000000, confirmation_base: 33000000, minimum_sessions: 2000}\n"
          + "statistics: {block_sessions: 21, resamples: 2000, bootstrap_seed: 5}\nsmallest_effect: {usd_per_session: 100}\n";

  static String prereg(final String dataSet, final String extra) {
    return "prereg_version: 2\nid: X\ntitle: t\nhypothesis: h\nmetric: m\nbaseline: a\ntreatments: [b]\ntest: t\n"
        + "data_set:\n" + dataSet
        + "  simulator_params: data/calibration/sim_v2_params.json\n"
        + "  simulator_params_sha256: 3e2159814ee4e9892ae0dba33f5f35fe0bdd33739df625ea4ae46162c1154fd4\n"
        + "success_threshold: s\nassumption_band: {preset: sim_v2_params, parameters: [impact], points: [low, central, high]}\n"
        + "author: a\ndate: 2026-11-23\nconfig: c.yaml\nconfig_sha256: CONFIG_SHA\n" + extra;
  }

  @Test
  void everyRuleNamesItsProblem(@TempDir final Path dir) throws IOException {
    final Path repo = repo(dir);
    assertEquals(List.of(), problems(repo, prereg("  seed_base: 33000000\n  sessions: 2000\n", ""), GOOD_CONFIG));
    assertEquals(
        List.of("missing or blank key 'title'", "version 1 date key 'holdout' is not allowed in a session-seed design"),
        problems(repo, prereg("  seed_base: 33000000\n  sessions: 2000\n", "holdout: [2026-01-02]\n").replace("title: t\n", ""), GOOD_CONFIG));
    assertEquals(
        List.of(
            "data_set.seed_base: seed base 8000000 is spent: every session under 8000000 was generated by the old simulators and read by AUDIT-01 to AUDIT-26 (SIM_V2_DESIGN.md section 12)",
            "data_set.sessions 50 is below the floor of 2000",
            "config seeds.confirmation_base 33000000 differs from data_set.seed_base 8000000"),
        problems(repo, prereg("  seed_base: 8000000\n  sessions: 50\n", ""), GOOD_CONFIG));
    assertEquals(
        List.of(
            "data_set.seed_base: seed base 29000000 is the pilot base: it sizes a confirmation set and is then discarded for inference",
            "data_set.sessions must be an integer of at least 2000 once the file is not a draft",
            "config seeds.confirmation_base 33000000 differs from data_set.seed_base 29000000"),
        problems(repo, prereg("  seed_base: 29000000\n  sessions: rule\n", ""), GOOD_CONFIG));
    assertEquals(
        List.of(
            "data_set.seed_base: seed base 28000000 belongs to the simulator's acceptance tests and supports no claim",
            "data_set.first + sessions exceeds 80000: later sessions' label dates (2030-01-01 plus the session number in days) overflow epoch nanoseconds",
            "config seeds.confirmation_base 33000000 differs from data_set.seed_base 28000000"),
        problems(repo, prereg("  seed_base: 28000000\n  first: 79000\n  sessions: 2000\n", ""), GOOD_CONFIG));
    // a draft may give the rule as text
    assertEquals(List.of(), problems(repo, prereg("  seed_base: 33000000\n  sessions: rule\n", "status: draft\n"), GOOD_CONFIG));
    assertEquals(
        List.of(
            "config arms do not name the treatment 'b'",
            "config statistics.resamples must be a positive integer",
            "config seeds.pilot_base must be the pilot base 29000000, found 33000000",
            "config seeds.minimum_sessions 100 is below the design's floor of 2000",
            "config guards.speed is not a guard; known: [quoted_share, hit_rate, client_dv01]",
            "config guards.hit_rate must be a positive ratio",
            "config smallest_effect.usd_per_session must be a positive number, stated before the pilot"),
        problems(
            repo, prereg("  seed_base: 33000000\n  sessions: 2000\n", ""),
            "arms: {a: x}\nseeds: {pilot_base: 33000000, minimum_sessions: 100}\n"
                + "statistics: {block_sessions: 21, resamples: 0, bootstrap_seed: 5}\nguards: {speed: 1, hit_rate: 0}\n"));
    assertEquals(
        List.of(
            "missing or blank key 'test': a comparison names its test",
            "the baseline arm is also listed as a treatment",
            "date is not an ISO date: yesterday",
            "prior_looks must be a list of pre-registration ids",
            "assumption_band.preset must be sim_v2_params, found x",
            "assumption_band.parameters has an unknown parameter 'speed'; known: [winner_half_spread, all_in_cost, informed_fraction, drift_tau, level_vol, spread_factor, impact, basis, taker_skill, impact_half_life]",
            "assumption_band.points has an unknown point 'middle'",
            "assumption_band.points must be low, central and high (DR-008)",
            "config must name the arms under 'arms'",
            "config must state the statistics under 'statistics'",
            "config must state the pilot base under 'seeds.pilot_base'",
            "config smallest_effect.usd_per_session must be a positive number, stated before the pilot"),
        problems(
            repo,
            prereg("  seed_base: 33000000\n  sessions: 2000\n", "prior_looks: none\n")
                .replace("test: t\n", "")
                .replace("treatments: [b]", "treatments: [b, a]")
                .replace("date: 2026-11-23", "date: yesterday")
                .replace("{preset: sim_v2_params, parameters: [impact], points: [low, central, high]}",
                    "{preset: x, parameters: [speed], points: [low, middle]}"),
            "other: 1\n"));
    assertEquals(
        List.of(
            "treatments must be a list (empty for a description)",
            "data_set.seed_base must be an integer",
            "data_set.first must be a non-negative integer",
            "data_set.simulator_params_sha256 does not match data/calibration/sim_v2_params.json (3e2159814ee4e9892ae0dba33f5f35fe0bdd33739df625ea4ae46162c1154fd4)",
            "assumption_band.parameters must be a list of band parameter names",
            "assumption_band.points must be a list",
            "config file not found: missing.yaml"),
        problems(
            repo,
            prereg("  seed_base: x\n  first: -1\n  sessions: 2\n", "")
                .replace("treatments: [b]", "treatments: b")
                .replace("1c154fd4\n", "1c154fd5\n")
                .replace("parameters: [impact], points: [low, central, high]", "parameters: impact, points: low")
                .replace("config: c.yaml", "config: missing.yaml"),
            GOOD_CONFIG));
    assertEquals(
        List.of("data_set must be a mapping with seed_base, sessions and simulator_params", "assumption_band must be a mapping"),
        problems(
            repo,
            "prereg_version: 2\nid: X\ntitle: t\nhypothesis: h\nmetric: m\nbaseline: a\ntreatments: []\ndata_set: [1]\n"
                + "success_threshold: s\nassumption_band: [1]\nauthor: a\ndate: 2026-11-23\nconfig: c.yaml\nconfig_sha256: CONFIG_SHA\n",
            GOOD_CONFIG.replace("smallest_effect: {usd_per_session: 100}\n", "")));
  }

  @Test
  void sessionSeedRowsAreKeyedLikeThePythonTwin() {
    // the same literal is asserted in research/tests/test_prereg_v2.py
    assertEquals("sessions:98deedb51466d9ff", DataSets.sessionsKey(30_000_000L, 0, 3));
    assertEquals("sessions:1099e6645df9e59b", DataSets.sessionsKey(32_000_000L, 0, 2000));
    assertEquals(PairedSessions.dataSetKey(30_000_000L, 0, 3), DataSets.sessionsKey(30_000_000L, 0, 3));
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("prereg_id", "X");
    row.put("session_seeds", Map.of("base", 30_000_000L, "first", 0L, "count", 3L));
    assertEquals("sessions:98deedb51466d9ff", DataSets.key(row));
    row.put("seq", 1L);
    assertTrue(DataSets.report(List.of(row)).contains("3 simulator v2 sessions, seeds 30000000..30000002: 1 look"));
  }

  private static Map<String, Object> row(
      final String id, final String dataSet, final double p, final double low, final double high, final Map<String, Object> signs,
      final Map<String, Object> guardsMin, final double quotedRatio) {
    final Map<String, Object> c = new LinkedHashMap<>();
    c.put("paired_p", p);
    c.put("ci_low", low);
    c.put("ci_high", high);
    c.put("guards", Map.of("quoted_share_ratio", quotedRatio));
    final Map<String, Object> results = new LinkedHashMap<>();
    results.put("primary", Map.of("t", c));
    results.put("signs_by_band", Map.of("t", signs));
    final Map<String, Object> r = new LinkedHashMap<>();
    r.put("prereg_id", id);
    r.put("data_set", dataSet);
    r.put("guards_min", guardsMin);
    r.put("results", results);
    return r;
  }

  @Test
  void theFamilyReportAdjustsOnRequestAndGivesEachVerdict(@TempDir final Path dir) throws IOException {
    final Ledger ledger = new Ledger(dir.resolve("ledger.jsonl"));
    final Map<String, Object> plus = Map.of("central", 1L, "impact:low", 1L);
    final Map<String, Object> mixed = Map.of("central", 1L, "impact:low", -1L);
    ledger.append(row("A", "sessions:a", 0.001, 10.0, 20.0, plus, Map.of("quoted_share", 0.8), 0.9));
    ledger.append(row("B", "sessions:b", 0.004, 10.0, 20.0, plus, Map.of("quoted_share", 0.8), 0.5));
    ledger.append(row("C", "sessions:c", 0.010, -20.0, -10.0, Map.of("central", -1L), Map.of(), 1.0));
    ledger.append(row("D", "sessions:d", 0.300, -5.0, 9.0, mixed, Map.of(), 1.0));
    ledger.append(row("E", "sessions:e", 0.020, 1.0, 9.0, plus, Map.of(), 1.0));
    ledger.append(row("F", "sessions:f", 0.900, -5.0, 9.0, plus, Map.of(), 1.0));
    final Path family = dir.resolve("family.yaml");
    Files.writeString(family, "family: T\nq: 0.05\nmembers: [A, B, C, D, E, F, Z]\n", StandardCharsets.UTF_8);
    final String expected =
        "family T (q = 0.05): 6 tests from 6 ledger rows\n"
            + "  A t_vs_baseline  seq 1  p 0.00100000  bh 0.00600000  ci [10.00, 20.00]  signs +2/-0  guards ok  verdict supported\n"
            + "  B t_vs_baseline  seq 2  p 0.00400000  bh 0.01200000  ci [10.00, 20.00]  signs +2/-0  guards fail  verdict trading less\n"
            + "  C t_vs_baseline  seq 3  p 0.01000000  bh 0.02000000  ci [-20.00, -10.00]  signs +0/-1  guards unchecked  verdict reversed\n"
            + "  D t_vs_baseline  seq 4  p 0.30000000  bh 0.36000000  ci [-5.00, 9.00]  signs +1/-1  guards unchecked  verdict not robust\n"
            + "  E t_vs_baseline  seq 5  p 0.02000000  bh 0.03000000  ci [1.00, 9.00]  signs +2/-0  guards unchecked  verdict supported if the guards hold\n"
            + "  F t_vs_baseline  seq 6  p 0.90000000  bh 0.90000000  ci [-5.00, 9.00]  signs +2/-0  guards unchecked  verdict inconclusive\n";
    assertEquals(expected, FamilyAdjustment.report(ledger.entries(), FamilyAdjustment.Family.of(family.toString())));
    assertEquals(
        "family data_set:sessions:c (q = 0.05): 1 test from 1 ledger row\n"
            + "  C t_vs_baseline  seq 3  p 0.01000000  bh 0.01000000  ci [-20.00, -10.00]  signs +0/-1  guards unchecked  verdict reversed\n",
        FamilyAdjustment.report(ledger.entries(), FamilyAdjustment.Family.of("data_set:sessions:c")));
    assertEquals(
        "family data_set:none (q = 0.05): 0 tests from 0 ledger rows\n",
        FamilyAdjustment.report(ledger.entries(), FamilyAdjustment.Family.of("data_set:none")));
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      assertEquals(0, LedgerCli.run(new String[] {"--ledger", ledger.file().toString(), "--bh", family.toString()}, out));
      assertEquals(1, LedgerCli.run(new String[] {"--ledger", ledger.file().toString(), "--bh", "no-such-file"}, out));
    }
    final String text = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("verdict supported\n") && text.contains("family file not found: no-such-file"), text);
    // nothing about the adjustment is written back into the ledger
    assertFalse(Files.readString(ledger.file()).contains("bh"));
  }

  @Test
  void aVersionOneHarnessRefusesASessionSeedDesign() {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      final Path repo = TestSupport.repoRoot();
      assertEquals(
          ExperimentCli.REFUSED,
          ExperimentCli.run(new String[] {"--prereg", "research/prereg/SIM3-BASE-001.yaml", "--repo", repo.toString()}, out));
    }
    assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("session-seed design"));
  }
}
