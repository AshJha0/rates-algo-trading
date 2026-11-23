package io.ratesalgo.backtest.experiment;

import io.ratesalgo.backtest.TestSupport;
import io.ratesalgo.backtest.util.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A scratch repository with a version 2 pre-registration, outside git. */
final class HarnessFixtures {

  private HarnessFixtures() {}

  /** Output of a command run. */
  record Run(int code, String out) {}

  static Run run(final String... args) {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      final int code = ExperimentRunner.run(args, out);
      return new Run(code, bytes.toString(StandardCharsets.UTF_8));
    }
  }

  /** Copies the presets and the simulator parameters into a fresh repository root. */
  static Path repo(final Path dir) {
    try {
      final Path repo = dir.resolve("repo");
      Files.createDirectories(repo.resolve("data/calibration"));
      Files.createDirectories(repo.resolve("research/prereg"));
      Files.copy(TestSupport.repoRoot().resolve("data/presets.yaml"), repo.resolve("data/presets.yaml"));
      Files.copy(
          TestSupport.repoRoot().resolve("data/calibration/sim_v2_params.json"),
          repo.resolve("data/calibration/sim_v2_params.json"));
      return repo;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Writes pre-registration {@code id} with its config and returns its path relative to the repo.
   *
   * @param baseline the baseline arm
   * @param treatments YAML list of treatment arms, e.g. {@code []}
   * @param arms YAML mapping of every arm
   * @param sessions the data set's session count (YAML scalar)
   * @param parameters YAML list of band parameters
   * @param extra further pre-registration lines (status, prior_looks)
   */
  static String prereg(
      final Path repo, final String id, final long base, final String baseline, final String treatments,
      final String arms, final String sessions, final String parameters, final String extra) {
    try {
      final Path config = repo.resolve("research/prereg/" + id + ".config.yaml");
      Files.writeString(
          config,
          "arms: " + arms + "\n"
              + "seeds: {pilot_base: 29000000}\n"
              + "statistics: {block_sessions: 21, resamples: 200, bootstrap_seed: 5}\n"
              + "smallest_effect: {usd_per_session: 1000}\n"
              + "guards: {quoted_share: 0.8}\n",
          StandardCharsets.UTF_8);
      final String text =
          "prereg_version: 2\n"
              + extra
              + "id: " + id + "\n"
              + "title: harness test\n"
              + "hypothesis: none\n"
              + "metric: P&L per session\n"
              + "baseline: " + baseline + "\n"
              + "treatments: " + treatments + "\n"
              + "test: paired t\n"
              + "data_set:\n"
              + "  seed_base: " + base + "\n"
              + "  sessions: " + sessions + "\n"
              + "  simulator_params: data/calibration/sim_v2_params.json\n"
              + "  simulator_params_sha256: " + Hashing.sha256TextFile(repo.resolve("data/calibration/sim_v2_params.json")) + "\n"
              + "success_threshold: none\n"
              + "assumption_band: {preset: sim_v2_params, parameters: " + parameters + ", points: [low, central, high]}\n"
              + "author: test\n"
              + "date: 2026-11-23\n"
              + "config: research/prereg/" + id + ".config.yaml\n"
              + "config_sha256: " + Hashing.sha256TextFile(config) + "\n";
      Files.writeString(repo.resolve("research/prereg/" + id + ".yaml"), text, StandardCharsets.UTF_8);
      return "research/prereg/" + id + ".yaml";
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A two-session description on the exploration base with the idle toy arm. */
  static String description(final Path repo, final String id) {
    return prereg(repo, id, 30_000_000L, "idle", "[]", "{idle: toy}", "2", "[]", "");
  }
}
