package io.ratesalgo.backtest.prereg;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.CompetitorModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.strategy.FixedSpreadStrategy;
import io.ratesalgo.backtest.strategy.ModelSpreadStrategy;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.backtest.util.Yaml;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a pre-registered comparison of strategies over synthetic days at every point of the
 * declared assumption band, and computes the statistics the pre-registration names
 * (API_SPEC.md Section 16.8).
 *
 * <p>For each day the synthetic journal is generated from the day's seed ({@code day_seed_base}
 * plus the epoch day), hashed, replayed through every strategy at every band point with the same
 * outcome seed, and deleted. The per-day total P&amp;L, rounded to cents, is the unit of analysis.
 * For every treatment and band point: Welch's test of treatment against baseline daily P&amp;L,
 * Holm's adjustment over all (treatment, band point) tests, and a moving-block bootstrap interval
 * of the paired daily difference.
 *
 * <p>Verdict per band point: {@code supported} if the Holm-adjusted p is below {@code alpha} and
 * the bootstrap interval of the difference lies above zero; {@code reversed} if the p is below
 * {@code alpha} and the interval lies below zero; otherwise {@code inconclusive}. Overall: {@code
 * supported} only if supported at every band point; otherwise {@code not supported}.
 */
public final class Experiment {

  /** The verdict and the results map recorded in the ledger. */
  public record Outcome(String verdict, Map<String, Object> results, List<Map<String, Object>> journals) {
    public Outcome {
      results = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(results));
      journals = List.copyOf(journals);
    }
  }

  private final Path repoRoot;
  private final Map<String, Object> config;
  private final Presets presets;

  public Experiment(final Path repoRoot, final Path configFile) {
    this.repoRoot = repoRoot;
    this.config = Yaml.loadMap(configFile);
    this.presets = Presets.load(repoRoot.resolve(Yaml.string(config, "presets", configFile.toString())));
  }

  public Presets presets() {
    return presets;
  }

  /** Builds a strategy by name from the experiment config. */
  public Strategy strategy(final String name) {
    final Map<String, Object> all = Yaml.map(config, "strategies", "experiment config");
    final Map<String, Object> m = Yaml.map(all, name, "strategies");
    final int perTier = (int) presets.get("usd_synthetic_market").value("clients_per_tier");
    return switch (name) {
      case FixedSpreadStrategy.NAME -> FixedSpreadStrategy.fromConfig(m, perTier);
      case ModelSpreadStrategy.NAME ->
          ModelSpreadStrategy.fromConfig(
              m,
              HitRateModel.fromPreset(presets.get(HitRateModel.PRESET), BandPoint.CENTRAL),
              CompetitorModel.fromPreset(presets.get(CompetitorModel.PRESET)),
              perTier);
      default -> throw new IllegalArgumentException("unknown strategy " + name);
    };
  }

  public long daySeed(final LocalDate day) {
    return Yaml.integer(config, "day_seed_base", "experiment config") + day.toEpochDay();
  }

  public long outcomeSeed() {
    return Yaml.integer(config, "outcome_seed", "experiment config");
  }

  /** Runs {@code baseline} and {@code treatments} on {@code days}, using {@code workDir} for journals. */
  public Outcome run(
      final String baseline, final List<String> treatments, final List<LocalDate> days, final Path workDir) {
    final Map<String, Object> st = Yaml.map(config, "statistics", "experiment config");
    final int block = (int) Yaml.integer(st, "bootstrap_block_days", "statistics");
    final int resamples = (int) Yaml.integer(st, "bootstrap_resamples", "statistics");
    final long bootSeed = Yaml.integer(st, "bootstrap_seed", "statistics");
    final double level = Yaml.number(st, "level", "statistics");
    final double alpha = Yaml.number(st, "alpha", "statistics");

    final List<String> names = new ArrayList<>();
    names.add(baseline);
    names.addAll(treatments);
    final BandPoint[] points = BandPoint.values();
    // pnl[point][strategy][day]
    final double[][][] pnl = new double[points.length][names.size()][days.size()];
    final Map<String, Map<String, Accumulator>> acc = new LinkedHashMap<>();
    final List<Map<String, Object>> journals = new ArrayList<>();
    final SyntheticDay generator = new SyntheticDay(SyntheticDay.Params.fromPresets(presets));
    try {
      Files.createDirectories(workDir);
      for (int d = 0; d < days.size(); d++) {
        final LocalDate day = days.get(d);
        final Path journal = workDir.resolve("synthetic-" + day + ".raj");
        Files.deleteIfExists(journal);
        final SyntheticDay.Summary summary = generator.generate(day, daySeed(day), journal, null);
        final Map<String, Object> j = new LinkedHashMap<>();
        j.put("date", day.toString());
        j.put("seed", daySeed(day));
        j.put("sha256", Hashing.sha256File(journal));
        j.put("frames", summary.frames());
        j.put("rfqs", (long) summary.rfqs());
        journals.add(j);
        for (int p = 0; p < points.length; p++) {
          final BacktestConfig cfg =
              BacktestConfig.fromPresets(presets, List.of(journal), outcomeSeed(), points[p], null);
          for (int s = 0; s < names.size(); s++) {
            final BacktestResult r = new BacktestRunner(cfg).run(strategy(names.get(s)));
            pnl[p][s][d] = Prices.cents(r.total().positionPnl());
            acc.computeIfAbsent(points[p].label(), k -> new LinkedHashMap<>())
                .computeIfAbsent(names.get(s), k -> new Accumulator())
                .add(r);
          }
        }
        Files.delete(journal);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    // tests and Holm across every (treatment, band point)
    final int tests = points.length * treatments.size();
    final double[] rawP = new double[tests];
    final Stats.Welch[] welch = new Stats.Welch[tests];
    final Stats.Interval[] diffCi = new Stats.Interval[tests];
    for (int p = 0; p < points.length; p++) {
      for (int t = 0; t < treatments.size(); t++) {
        final int k = p * treatments.size() + t;
        final double[] treat = pnl[p][t + 1];
        final double[] base = pnl[p][0];
        welch[k] = Stats.welch(treat, base);
        rawP[k] = welch[k].p();
        final double[] diff = new double[days.size()];
        for (int d = 0; d < diff.length; d++) {
          diff[d] = treat[d] - base[d];
        }
        diffCi[k] = Stats.blockBootstrapMeanCi(diff, block, resamples, level, bootSeed);
      }
    }
    final double[] holm = Stats.holm(rawP);

    final Map<String, Object> results = new LinkedHashMap<>();
    final List<String> dayList = new ArrayList<>();
    for (final LocalDate d : days) {
      dayList.add(d.toString());
    }
    results.put("days", dayList);
    results.put("metric_unit", "USD per synthetic day, total position P&L rounded to cents");
    final Map<String, Object> byBand = new LinkedHashMap<>();
    final Map<String, Object> verdicts = new LinkedHashMap<>();
    boolean allSupported = true;
    for (int p = 0; p < points.length; p++) {
      final Map<String, Object> band = new LinkedHashMap<>();
      final Map<String, Object> strategies = new LinkedHashMap<>();
      for (int s = 0; s < names.size(); s++) {
        final double[] x = pnl[p][s];
        final Stats.Interval ci = Stats.blockBootstrapMeanCi(x, block, resamples, level, bootSeed);
        final Map<String, Object> m = new LinkedHashMap<>();
        final List<Double> daily = new ArrayList<>();
        for (final double v : x) {
          daily.add(v);
        }
        m.put("daily_pnl", daily);
        m.put("mean", Prices.cents(ci.estimate()));
        m.put("ci_low", Prices.cents(ci.lower()));
        m.put("ci_high", Prices.cents(ci.upper()));
        m.putAll(acc.get(points[p].label()).get(names.get(s)).summary());
        strategies.put(names.get(s), m);
      }
      band.put("strategies", strategies);
      final Map<String, Object> comparisons = new LinkedHashMap<>();
      for (int t = 0; t < treatments.size(); t++) {
        final int k = p * treatments.size() + t;
        final Map<String, Object> c = new LinkedHashMap<>();
        c.put("mean_difference", Prices.cents(welch[k].difference()));
        c.put("paired_ci_low", Prices.cents(diffCi[k].lower()));
        c.put("paired_ci_high", Prices.cents(diffCi[k].upper()));
        c.put("welch_t", round(welch[k].t(), 6));
        c.put("welch_df", round(welch[k].df(), 6));
        c.put("welch_p", round(welch[k].p(), 8));
        c.put("holm_p", round(holm[k], 8));
        final String v;
        if (holm[k] < alpha && diffCi[k].lower() > 0) {
          v = "supported";
        } else if (holm[k] < alpha && diffCi[k].upper() < 0) {
          v = "reversed";
        } else {
          v = "inconclusive";
        }
        c.put("verdict", v);
        allSupported &= "supported".equals(v);
        comparisons.put(treatments.get(t) + "_vs_" + baseline, c);
        verdicts.put(points[p].label() + ":" + treatments.get(t), v);
      }
      band.put("comparisons", comparisons);
      byBand.put(points[p].label(), band);
    }
    results.put("band", byBand);
    results.put("verdict_by_band", verdicts);
    results.put(
        "statistics",
        Map.of(
            "bootstrap_block_days", (long) block,
            "bootstrap_resamples", (long) resamples,
            "bootstrap_seed", bootSeed,
            "level", level,
            "alpha", alpha,
            "multiple_comparison", "holm over " + tests + " tests"));
    return new Outcome(allSupported ? "supported" : "not supported", results, journals);
  }

  private static double round(final double v, final int digits) {
    final double f = Math.pow(10, digits);
    return Math.round(v * f) / f;
  }

  /** Sums over days for one strategy at one band point. */
  private static final class Accumulator {
    long rfqs;
    long quoted;
    long won;
    long hedges;
    Attribution attribution = Attribution.ZERO;
    double maxResidual;

    void add(final BacktestResult r) {
      rfqs += r.rfqs().size();
      for (final RfqRecord q : r.rfqs()) {
        if (q.quoted()) {
          quoted++;
        }
        if (q.won()) {
          won++;
        }
      }
      hedges += r.hedgeFills();
      attribution = attribution.plus(r.total());
      maxResidual = Math.max(maxResidual, Math.abs(r.total().residual()));
    }

    Map<String, Object> summary() {
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("rfqs", rfqs);
      m.put("quoted", quoted);
      m.put("won", won);
      m.put("hit_rate", quoted == 0 ? 0.0 : Math.round(1e6 * won / (double) quoted) / 1e6);
      m.put("hedge_fills", hedges);
      final Map<String, Object> a = new LinkedHashMap<>();
      for (final Map.Entry<String, Object> e : attribution.asMap().entrySet()) {
        a.put(e.getKey(), Prices.cents((Double) e.getValue()));
      }
      m.put("attribution_total", a);
      m.put("max_abs_residual", maxResidual);
      return m;
    }
  }

  public Path repoRoot() {
    return repoRoot;
  }
}
