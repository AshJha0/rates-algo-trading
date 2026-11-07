package io.ratesalgo.strategy.experiment;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.RiskCarry;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.prereg.PreRegistration;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.strategy.DeskQuoter;
import io.ratesalgo.strategy.report.DeskMetrics;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exploratory sensitivity of the desk to hedge order latency, and how long it carries client risk
 * before a hedge trades ({@link RiskCarry}). Not a pre-registered test: it runs only the fitting
 * period of the pre-registration it is given, records nothing in the ledger and reports means,
 * not verdicts.
 *
 * <pre>
 *   LatencySweep --prereg data/preregs/V1H-001.yaml [--latencies-ms 0,2,20,200,1000,5000]
 *                [--repo .] [--work target/latency-sweep] [--out research/hedge/results/latency_sweep.json]
 * </pre>
 *
 * For every band point, fitting day, latency and strategy ({@value HedgeExperiment#BASELINE} and
 * {@value HedgeExperiment#DESK_DEFAULT}) it replays the same journal with the order latency set
 * to that value, everything else as in {@link HedgeExperiment}. Carry quantiles are pooled over
 * the client fills of all days at a cell.
 */
public final class LatencySweep {

  public static final List<String> STRATEGIES = List.of(HedgeExperiment.BASELINE, HedgeExperiment.DESK_DEFAULT);
  public static final List<String> MEANS =
      List.of("pnl", "hedge_shortfall", "hedge_slippage", "risk_adjusted_shortfall", "hedges", "mean_abs_net_dv01",
          "max_abs_net_dv01");

  private LatencySweep() {}

  public static void main(final String[] args) {
    run(args, System.out);
  }

  /** Runs the sweep, writes the JSON and prints a table; returns the result map. */
  public static Map<String, Object> run(final String[] args, final PrintStream out) {
    final Map<String, String> opts = DeskExperimentCli.parse(args);
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final Path work = Path.of(opts.getOrDefault("work", "target/latency-sweep")).toAbsolutePath();
    final PreRegistration prereg =
        PreRegistration.load(repo.resolve(opts.getOrDefault("prereg", "data/preregs/V1H-001.yaml")));
    final double[] latenciesMs =
        Arrays.stream(opts.getOrDefault("latencies-ms", "0,2,20,200,1000,5000").split(",", -1))
            .mapToDouble(s -> Double.parseDouble(s.trim())).toArray();
    final List<LocalDate> days = prereg.fittingPeriod().businessDays();
    final Map<String, Object> result = sweep(repo, repo.resolve(prereg.config()), days, latenciesMs, work);
    result.put("prereg", prereg.id());
    final Path outFile = repo.resolve(opts.getOrDefault("out", "research/hedge/results/latency_sweep.json"));
    try {
      Files.createDirectories(outFile.getParent());
      Files.writeString(outFile, Json.write(result) + "\n");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    print(result, out);
    out.println("written " + repo.relativize(outFile).toString().replace('\\', '/'));
    return result;
  }

  /** The sweep itself: band point, then strategy, then latency. */
  public static Map<String, Object> sweep(
      final Path repo, final Path config, final List<LocalDate> days, final double[] latenciesMs, final Path work) {
    final HedgeExperiment hedge = new HedgeExperiment(repo, config);
    final DeskExperiment desk = new DeskExperiment(repo, config);
    final Map<String, Object> band = new LinkedHashMap<>();
    try {
      Files.createDirectories(work);
      for (final BandPoint point : BandPoint.values()) {
        final Presets presets = desk.presets(point);
        final DeskFactory factory = desk.factory(presets);
        final DeskMetrics.Settings settings = DeskExperiment.settings(factory);
        final SyntheticDay generator = new SyntheticDay(SyntheticDay.Params.fromPresets(presets));
        // cell[s][l]: per-day sessions and pooled carry times
        final List<List<List<HedgeExperiment.Session>>> sessions = new ArrayList<>();
        final List<List<List<Double>>> carry = new ArrayList<>();
        final long[][] clients = new long[STRATEGIES.size()][latenciesMs.length];
        final long[][] unhedged = new long[STRATEGIES.size()][latenciesMs.length];
        for (int s = 0; s < STRATEGIES.size(); s++) {
          sessions.add(new ArrayList<>());
          carry.add(new ArrayList<>());
          for (int l = 0; l < latenciesMs.length; l++) {
            sessions.get(s).add(new ArrayList<>());
            carry.get(s).add(new ArrayList<>());
          }
        }
        for (final LocalDate day : days) {
          final Path journal = work.resolve("latency-" + point.label() + "-" + day + ".raj");
          Files.deleteIfExists(journal);
          generator.generate(day, desk.daySeed(day), journal, null);
          final BacktestConfig base = hedge.backtestConfig(presets, factory, journal, point);
          for (int l = 0; l < latenciesMs.length; l++) {
            final BacktestConfig cfg = withOrderLatency(base, Math.round(latenciesMs[l] * 1e6));
            for (int s = 0; s < STRATEGIES.size(); s++) {
              final DeskQuoter q = hedge.strategy(STRATEGIES.get(s), presets, factory);
              final BacktestResult r = new BacktestRunner(cfg).run(q);
              sessions.get(s).get(l).add(HedgeExperiment.session(DeskMetrics.of(r, cfg.instruments(), settings), q, settings));
              final RiskCarry rc = RiskCarry.of(r.fills());
              clients[s][l] += rc.clientFills();
              unhedged[s][l] += rc.unhedgedClientFills();
              for (final double c : rc.carrySeconds()) {
                carry.get(s).get(l).add(c);
              }
            }
          }
          Files.delete(journal);
        }
        final Map<String, Object> byStrategy = new LinkedHashMap<>();
        for (int s = 0; s < STRATEGIES.size(); s++) {
          final List<Map<String, Object>> rows = new ArrayList<>();
          for (int l = 0; l < latenciesMs.length; l++) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("order_latency_ms", latenciesMs[l]);
            for (final String key : MEANS) {
              row.put(key, round(mean(sessions.get(s).get(l), key), 2));
            }
            final RiskCarry pooled =
                new RiskCarry(clients[s][l], unhedged[s][l],
                    carry.get(s).get(l).stream().mapToDouble(Double::doubleValue).sorted().toArray());
            row.put("client_fills", clients[s][l]);
            row.put("unhedged_client_fills", unhedged[s][l]);
            row.put("carry_mean_s", round(pooled.mean(), 3));
            row.put("carry_p50_s", round(pooled.quantile(0.5), 3));
            row.put("carry_p90_s", round(pooled.quantile(0.9), 3));
            row.put("carry_max_s", round(pooled.quantile(1.0), 3));
            rows.add(row);
          }
          byStrategy.put(STRATEGIES.get(s), rows);
        }
        band.put(point.label(), byStrategy);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", "exploratory sensitivity, fitting period only, no ledger record, no verdict");
    result.put("days", days.stream().map(LocalDate::toString).toList());
    result.put("latencies_ms", Arrays.stream(latenciesMs).boxed().toList());
    result.put("units", "USD per synthetic session (means over days); DV01 in USD per bp; carry in seconds");
    result.put(
        "carry_definition",
        "time from each client fill to the first hedge fill at or after it; client fills with no later hedge "
            + "in the session are counted as unhedged");
    result.put("band", band);
    return result;
  }

  static BacktestConfig withOrderLatency(final BacktestConfig c, final long nanos) {
    return new BacktestConfig(
        c.journals(), c.instruments(), c.seed(), c.bandPoint(), c.outcomes(), c.strategyHitRate(), c.hedgeCost(),
        c.clob(), c.carry(), c.calendar(), c.clientsPerTier(), c.quoteLatencyNanos(), c.clientDecisionNanos(), nanos,
        c.markoutHorizonsNanos(), null);
  }

  @SuppressWarnings("unchecked")
  private static void print(final Map<String, Object> result, final PrintStream out) {
    final Map<String, Object> band = (Map<String, Object>) result.get("band");
    for (final Map.Entry<String, Object> p : band.entrySet()) {
      for (final Map.Entry<String, Object> s : ((Map<String, Object>) p.getValue()).entrySet()) {
        out.println(p.getKey() + " " + s.getKey());
        out.printf("  %9s %12s %12s %8s %12s %9s %9s %9s %9s%n", "lat_ms", "pnl", "shortfall", "hedges", "mean|dv01|",
            "carry50", "carry90", "carrymax", "unhedged");
        for (final Map<String, Object> r : (List<Map<String, Object>>) s.getValue()) {
          out.printf("  %9s %12s %12s %8s %12s %9s %9s %9s %9s%n",
              r.get("order_latency_ms"), r.get("pnl"), r.get("hedge_shortfall"), r.get("hedges"),
              r.get("mean_abs_net_dv01"), r.get("carry_p50_s"), r.get("carry_p90_s"), r.get("carry_max_s"),
              r.get("unhedged_client_fills") + "/" + r.get("client_fills"));
        }
      }
    }
  }

  private static double mean(final List<HedgeExperiment.Session> days, final String key) {
    double s = 0;
    for (final HedgeExperiment.Session d : days) {
      s += d.get(key);
    }
    return s / days.size();
  }

  /** Rounded value; null when there is none (the JSON writer refuses NaN). */
  private static Double round(final double v, final int digits) {
    if (Double.isNaN(v)) {
      return null;
    }
    final double f = Math.pow(10, digits);
    return Math.round(v * f) / f;
  }
}
