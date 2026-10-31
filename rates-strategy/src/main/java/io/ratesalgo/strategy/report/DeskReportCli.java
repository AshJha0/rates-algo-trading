package io.ratesalgo.strategy.report;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.strategy.DeskQuoter;
import io.ratesalgo.strategy.experiment.DeskExperiment;
import io.ratesalgo.strategy.experiment.DeskFactory;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the desk section of the daily report for one synthetic session (API_SPEC.md Section
 * 19.10).
 *
 * <pre>
 *   DeskReportCli --config data/experiments/R2B.config.yaml --date 2026-03-02
 *                 [--strategy baseline|internalisation|...|desk_all] [--band low|central|high]
 *                 [--repo .] [--out target/desk-report]
 * </pre>
 *
 * Output: {@code desk_report.json} and {@code desk_report.md} in the output directory.
 */
public final class DeskReportCli {

  private DeskReportCli() {}

  public static void main(final String[] args) {
    run(args, System.out);
  }

  /** Runs the command and returns the report. */
  public static DeskReport run(final String[] args, final PrintStream out) {
    final Map<String, String> opts = new LinkedHashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("expected --name value pairs, got " + args[i]);
      }
      opts.put(args[i].substring(2), args[i + 1]);
    }
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final Path outDir = Path.of(opts.getOrDefault("out", "target/desk-report")).toAbsolutePath();
    final LocalDate date = LocalDate.parse(opts.getOrDefault("date", "2026-03-02"));
    final String strategy = opts.getOrDefault("strategy", DeskFactory.DESK_ALL);
    final BandPoint point = BandPoint.parse(opts.getOrDefault("band", "central"));
    final DeskExperiment experiment =
        new DeskExperiment(repo, repo.resolve(opts.getOrDefault("config", "data/experiments/R2B.config.yaml")));
    final Presets presets = experiment.presets(point);
    final DeskFactory factory = experiment.factory(presets);
    try {
      Files.createDirectories(outDir);
      final Path journal = outDir.resolve("desk-" + date + ".raj");
      Files.deleteIfExists(journal);
      new SyntheticDay(SyntheticDay.Params.fromPresets(presets))
          .generate(date, experiment.daySeed(date), journal, null);
      final BacktestConfig cfg = experiment.backtestConfig(presets, factory, journal, point);
      final DeskQuoter q =
          DeskFactory.BASELINE.equals(strategy)
              ? factory.baseline()
              : factory.treatment(strategy, factory.newTiering());
      final BacktestResult r = new BacktestRunner(cfg).run(q);
      final DeskReport report =
          new DeskReport(date.toString(), strategy, DeskMetrics.of(r, cfg.instruments(), DeskExperiment.settings(factory)));
      Files.writeString(outDir.resolve("desk_report.json"), report.toJson() + "\n", StandardCharsets.UTF_8);
      Files.writeString(outDir.resolve("desk_report.md"), report.toMarkdown(), StandardCharsets.UTF_8);
      Files.delete(journal);
      out.println("wrote " + outDir.resolve("desk_report.md"));
      return report;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
