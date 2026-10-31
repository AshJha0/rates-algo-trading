package io.ratesalgo.backtest.report;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.ResultsWriter;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.prereg.Experiment;
import io.ratesalgo.backtest.util.Yaml;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the daily report unattended from journals and a report config (API_SPEC.md Section 16.9).
 *
 * <pre>
 *   ReportCli --config data/experiments/daily_report.yaml --journal day.raj[,day2.raj] --out dir [--repo .]
 * </pre>
 *
 * <p>The config names the experiment config whose strategies to use, the strategy, the band
 * points, the outcome seed, the limits and the optional latency JSON. For each band point the
 * runner writes {@code results.raj}, the CSV and JSON tables and {@code report.md} and {@code
 * report.json} under {@code out/<band point>/}. Exit code 0 on success, 1 on any error.
 */
public final class ReportCli {

  private ReportCli() {}

  public static void main(final String[] args) {
    final int code = run(args, System.out);
    if (code != 0) {
      System.exit(code);
    }
  }

  public static int run(final String[] args, final PrintStream out) {
    final java.util.Map<String, String> opts = new java.util.LinkedHashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      opts.put(args[i].replaceFirst("^--", ""), args[i + 1]);
    }
    if (!opts.containsKey("config") || !opts.containsKey("journal") || !opts.containsKey("out")) {
      out.println("usage: ReportCli --config FILE --journal FILE[,FILE] --out DIR [--repo DIR]");
      return 1;
    }
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final Map<String, Object> cfg = Yaml.loadMap(repo.resolve(opts.get("config")));
    final Experiment experiment = new Experiment(repo, repo.resolve(Yaml.string(cfg, "experiment_config", "report")));
    final List<Path> journals = new ArrayList<>();
    for (final String j : opts.get("journal").split(",", -1)) {
      journals.add(Path.of(j.trim()));
    }
    final Map<String, Object> limits = Yaml.map(cfg, "limits", "report");
    final Object lat = cfg.get("latency_json");
    final DailyReport.Settings settings =
        new DailyReport.Settings(
            Yaml.number(limits, "net_dv01_usd", "limits"),
            Yaml.number(limits, "position_mm", "limits"),
            lat == null ? null : repo.resolve(lat.toString()));
    final String strategy = Yaml.string(cfg, "strategy", "report");
    final long seed = Yaml.integer(cfg, "outcome_seed", "report");
    final Path outDir = Path.of(opts.get("out"));
    for (final Object p : Yaml.asList(Yaml.require(cfg, "band_points", "report"), "band_points")) {
      final BandPoint point = BandPoint.parse(p.toString());
      final Path dir = outDir.resolve(point.label());
      final BacktestConfig config = BacktestConfig.fromPresets(experiment.presets(), journals, seed, point, dir);
      final BacktestResult result = new BacktestRunner(config).run(experiment.strategy(strategy));
      ResultsWriter.write(result, config.instruments(), dir);
      final DailyReport report = new DailyReport(result, config.instruments(), settings, DailyReport.RouterFillQuality.NONE);
      try {
        Files.writeString(dir.resolve("report.md"), report.toMarkdown(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("report.json"), report.toJson(), StandardCharsets.UTF_8);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
      out.println(point.label() + ": " + result.rfqs().size() + " rfqs, P&L " + String.format(java.util.Locale.ROOT, "%.2f", result.total().positionPnl()) + " USD");
    }
    return 0;
  }
}
