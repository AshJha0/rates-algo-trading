package io.ratesalgo.backtest.experiment;

import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.sim.SimParams;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One worker process: runs one session of the run described by an output directory's manifest and
 * records it there. Exit 0 when the session's record is in place.
 *
 * <pre>
 *   SessionWorker --out DIR --seed S
 * </pre>
 *
 * <p>The worker checks that the simulator parameters it was built with are the ones the manifest
 * names (by SHA-256), so a run cannot mix sessions of two parameter files.
 */
public final class SessionWorker {

  private SessionWorker() {}

  public static void main(final String[] args) {
    final Map<String, String> opts = ExperimentRunner.parse(args, 0);
    try {
      runOne(new Checkpoints(Path.of(opts.get("out"))), Long.parseLong(opts.get("seed")));
    } catch (final RuntimeException e) {
      System.err.println("session failed: " + e);
      e.printStackTrace(System.err);
      System.exit(1);
    }
  }

  /** Runs and records one session of the manifest's run. */
  public static void runOne(final Checkpoints checkpoints, final long seed) {
    final Map<String, Object> manifest = checkpoints.readManifest();
    final String paramsSha = Hashing.sha256Text(SimParams.defaultParamsText());
    if (!paramsSha.equals(String.valueOf(manifest.get("simulator_params_sha256")))) {
      throw new IllegalStateException(
          "this build's simulator parameters (" + paramsSha + ") are not the run's (" + manifest.get("simulator_params_sha256") + ")");
    }
    final Path presetsFile = Path.of(String.valueOf(manifest.get("presets")));
    if (!Hashing.sha256TextFile(presetsFile).equals(String.valueOf(manifest.get("presets_sha256")))) {
      throw new IllegalStateException("the presets file changed since the run started: " + presetsFile);
    }
    final ArmFactory factory = ArmFactory.load(String.valueOf(manifest.get("factory")));
    final Path work = checkpoints.dir().resolve("work").resolve(Long.toString(seed));
    final Map<String, Object> record =
        new SessionExecutor(Presets.load(presetsFile), work)
            .run(seed, runs(manifest, seed), factory, checkpoints.parts(seed), Boolean.TRUE.equals(manifest.get("timing_only")),
                Long.MAX_VALUE);
    checkpoints.write(seed, record);
    checkpoints.clearParts(seed);
    deleteTree(work);
  }

  /**
   * The rows and arms of a manifest that this seed is in: with {@code row_plans} (a family) each
   * row's own arms, on its first {@code sessions} sessions; otherwise every arm on every row.
   */
  static List<SessionExecutor.RowRun> runs(final Map<String, Object> manifest, final long seed) {
    final SimParams central = SimParams.loadDefault();
    final List<SessionExecutor.RowRun> out = new ArrayList<>();
    if (manifest.get("row_plans") instanceof List<?> plans) {
      final Map<String, Object> seeds = Yaml.asMap(manifest.get("session_seeds"), "session_seeds");
      final long n = seed - ((Number) seeds.get("base")).longValue() - ((Number) seeds.get("first")).longValue();
      for (final Object o : plans) {
        final Map<String, Object> p = Yaml.asMap(o, "row plan");
        final long sessions = ((Number) p.get("sessions")).longValue();
        if (n < sessions) {
          out.add(new SessionExecutor.RowRun(BandParameters.row(central, String.valueOf(p.get("row"))), strings(p.get("arms"))));
        }
      }
      return out;
    }
    final List<String> arms = strings(manifest.get("arms"));
    for (final String label : strings(manifest.get("rows"))) {
      out.add(new SessionExecutor.RowRun(BandParameters.row(central, label), arms));
    }
    return out;
  }

  static List<String> strings(final Object list) {
    final List<String> out = new ArrayList<>();
    for (final Object o : Yaml.asList(list, "manifest list")) {
      out.add(String.valueOf(o));
    }
    return out;
  }

  private static void deleteTree(final Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> s = Files.walk(dir)) {
      for (final Path p : s.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
