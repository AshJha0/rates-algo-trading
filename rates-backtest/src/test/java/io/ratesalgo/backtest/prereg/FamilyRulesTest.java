package io.ratesalgo.backtest.prereg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.TestSupport;
import io.ratesalgo.backtest.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules of a registered family, with the fixtures of research/tests/test_prereg_family.py:
 * the Python twin must print the same problems in the same order.
 */
class FamilyRulesTest {

  static final String PREREG =
      "prereg_version: 2\nid: F\ntitle: t\nhypothesis: h\nmetric: m\nbaseline: default\n"
          + "treatments: [v2, x]\ntest: t\ndata_set:\n  seed_base: 31000000\n  sessions: 2000\n"
          + "  simulator_params: data/calibration/sim_v2_params.json\n  simulator_params_sha256:"
          + " 3e2159814ee4e9892ae0dba33f5f35fe0bdd33739df625ea4ae46162c1154fd4\n"
          + "success_threshold: s\nassumption_band: {preset: sim_v2_params, parameters: [impact,"
          + " drift_tau], points: [low, central, high]}\n"
          + "author: a\ndate: 2026-11-23\nconfig: c.yaml\nconfig_sha256: CONFIG_SHA\n";
  static final String STATS =
      """
      seeds: {pilot_base: 29000000, confirmation_base: 31000000}
      statistics: {block_sessions: 21, resamples: 2000, bootstrap_seed: 5}
      smallest_effect: {usd_per_session: 100}
      arms: {default: a, v2: b, x: c}
      """;
  static final String GOOD =
      STATS.replace("x: c}", "x: c, legacy: d}")
          + "family: {id: F, q: 0.05, reference_arm: default, guards_vs_reference: {client_dv01: 0.25},"
          + " descriptive_arms: [legacy], structural_declines: not_above_baseline, hypotheses: ["
          + "{id: H1, treatment: v2, baseline: default, guards: {quoted_share: 0.8}},"
          + " {id: H2, treatment: v2, baseline: x}]}\n"
          + "rows: [{row: central, arms: [default, v2, x, legacy], sessions: 2000},"
          + " {row: 'impact:low', arms: [default, v2], sessions: 1000},"
          + " {row: 'impact:high', arms: [default, v2], sessions: 1000}]\n"
          + "excluded_band_points: [{row: 'drift_tau:low', reason: refused},"
          + " {row: 'drift_tau:high', reason: refused}]\n";
  static final String BAD =
      STATS
          + "family: {q: 2, reference_arm: ghost, guards_vs_reference: {speed: 1},"
          + " descriptive_arms: [v2, ghost], hypotheses: ["
          + "{id: H1, treatment: v9, baseline: default, guards: {hit_rate: 0}},"
          + " {id: H1, treatment: x, baseline: x}, {treatment: v2}, {id: H3, treatment: ghost,"
          + " baseline: default}]}\n"
          + "rows: [{row: 'impact:low', arms: [default], sessions: 2000},"
          + " {row: 'impact:low', arms: [default, nobody], sessions: 5000},"
          + " {row: 'speed:low', arms: [], sessions: 0}]\n"
          + "excluded_band_points: [{row: 'impact:low'}, {row: 'drift_tau:middle', reason: r}]\n";
  static final String NEITHER =
      " is neither run (config rows) nor excluded with a reason (config excluded_band_points)";
  static final String SESSIONS = "sessions must be a positive integer no larger than data_set.sessions";

  private static List<String> problems(final Path repo, final String text, final String config) throws IOException {
    Files.writeString(repo.resolve("c.yaml"), config, StandardCharsets.UTF_8);
    Files.writeString(
        repo.resolve("p.yaml"), text.replace("CONFIG_SHA", Hashing.sha256Text(config)), StandardCharsets.UTF_8);
    return PreRegistration.load(repo.resolve("p.yaml")).validate(repo);
  }

  private static Path repo(final Path dir) throws IOException {
    Files.createDirectories(dir.resolve("data/calibration"));
    Files.copy(
        TestSupport.repoRoot().resolve("data/calibration/sim_v2_params.json"),
        dir.resolve("data/calibration/sim_v2_params.json"));
    return dir;
  }

  @Test
  void familyRulesNameTheirProblemsAsThePythonTwinDoes(@TempDir final Path dir) throws IOException {
    final Path repo = repo(dir);
    assertEquals(List.of(), problems(repo, PREREG, GOOD));
    assertEquals(
        List.of("config family.reference_arm 'nobody' is not an arm of the design"),
        problems(repo, PREREG, GOOD.replace("reference_arm: default", "reference_arm: nobody")));
    assertEquals(
        List.of(
            "config family.id is missing",
            "config family.q must be a number between 0 and 1",
            "config family.descriptive_arms: 'v2' is also the baseline or a treatment",
            "config family.descriptive_arms: 'ghost' is not named under config arms",
            "hypothesis H1: treatment 'v9' is not an arm of the design",
            "hypothesis H1: guards.hit_rate must be a positive ratio",
            "config family: hypothesis id 'H1' is listed twice",
            "hypothesis H1: treatment and baseline are the same arm",
            "config family.hypotheses[2] needs an id",
            "hypothesis H3: treatment 'ghost' is a descriptive arm and enters no hypothesis",
            "config family.reference_arm 'ghost' is a descriptive arm",
            "config family.guards_vs_reference.speed is not a guard; known: [quoted_share, hit_rate, client_dv01]",
            "config rows[0] must be the central row",
            "config rows: 'impact:low' is listed twice",
            "config rows 'impact:low': arm 'nobody' is not an arm of the design",
            "config rows 'impact:low': " + SESSIONS,
            "config rows: 'speed:low' is not a low or high row of the band's parameters",
            "config rows 'speed:low': arms must be a non-empty list",
            "config rows 'speed:low': " + SESSIONS,
            "config excluded_band_points: 'impact:low' is also run as a row",
            "config excluded_band_points 'impact:low' needs a reason",
            "config excluded_band_points: 'drift_tau:middle' is not a low or high row of the band's parameters",
            "assumption_band point 'impact:high'" + NEITHER,
            "assumption_band point 'drift_tau:low'" + NEITHER,
            "assumption_band point 'drift_tau:high'" + NEITHER),
        problems(repo, PREREG, BAD));
    assertEquals(
        List.of(
            "config family must be a mapping with id, q and hypotheses",
            "config rows[0] must be a mapping of row, arms and sessions",
            "config excluded_band_points must be a list of {row, reason}",
            "assumption_band point 'impact:low'" + NEITHER,
            "assumption_band point 'impact:high'" + NEITHER,
            "assumption_band point 'drift_tau:low'" + NEITHER,
            "assumption_band point 'drift_tau:high'" + NEITHER),
        problems(repo, PREREG, STATS + "family: [1]\nrows: [1]\nexcluded_band_points: 3\n"));
    final String noDefault = PREREG.replace("baseline: default", "baseline: d").replace("treatments: [v2, x]", "treatments: [v2]");
    final String cfg =
        STATS.replace("default: a", "d: a")
            + "family: {id: F, q: 0.05, guards_vs_default: {client_dv01: 1}, guards_vs_reference:"
            + " {client_dv01: 1}, descriptive_arms: 3, hypotheses: [{id: H1,"
            + " treatment: v2, baseline: d, guards: [1]}]}\nrows: [{row: central, arms: [d],"
            + " sessions: 7}]\nexcluded_band_points: [{row: 'impact:low', reason: r},"
            + " {row: 'impact:high', reason: r}, {row: 'drift_tau:low', reason: r},"
            + " {row: 'drift_tau:high', reason: r}]\n";
    assertEquals(
        List.of(
            "config family.descriptive_arms must be a list of arm names",
            "hypothesis H1: guards must be a mapping of guard name to minimum ratio",
            "config family.guards_vs_default is replaced by reference_arm and guards_vs_reference",
            "config family.guards_vs_reference needs family.reference_arm",
            "config rows[0] (central) must run every arm of the design",
            "config rows[0] sessions 7 differs from data_set.sessions 2000"),
        problems(repo, noDefault, cfg));
    // a config with none of the family keys is not a family
    assertFalse(FamilyRules.applies(Map.of("arms", Map.of())));
    assertTrue(FamilyRules.applies(Map.of("excluded_band_points", List.of())));
  }
}
