package io.ratesalgo.strategy.experiment;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.strategy.ClientTiering;
import io.ratesalgo.strategy.report.DeskMetrics;
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
 * Runs the seven pre-registered desk-feature experiments (R2B-001 to R2B-007) in one pass, so they
 * share journals, baseline runs and one Holm family (API_SPEC.md Section 19.9; DR-R2B-03).
 *
 * <p>For each band point and day: build the band's presets ({@link BandScenario}), generate the
 * synthetic journal from the day's seed, run the baseline and the seven treatments with the same
 * outcome seed (common random numbers), and compute {@link DeskMetrics}. The tiering treatments
 * carry one learned tiering per band point through the days in date order.
 *
 * <p>Per treatment and band point, with {@code d_i = orientation * (treatment_i - baseline_i)} on
 * day {@code i} of the treatment's pre-registered metric: a paired t-test of mean(d) against zero
 * ({@code t = mean / (sd / sqrt(n))}, {@code n - 1} degrees of freedom, two-sided), Holm's
 * adjustment over all 21 (treatment, band point) tests, and a moving-block bootstrap interval of
 * mean(d). A point is {@code improved} when the adjusted p is below alpha and the interval lies
 * above zero, {@code worse} when the adjusted p is below alpha and the interval lies below zero,
 * otherwise {@code inconclusive}. Guards at each point: no limit breach on a day on which the baseline did not breach (the
 * baseline itself breaches on days with a single very large RFQ), and the
 * bootstrap interval of the paired daily P&amp;L difference not entirely below zero. Verdict:
 * {@code supported} when improved at every point; adopted when supported and every guard holds.
 */
public final class DeskExperiment {

  /** A treatment's pre-registered metric and whether larger is better. */
  public record MetricSpec(String metric, int orientation, String unit) {}

  /** The metric each treatment is tested on. */
  public static final Map<String, MetricSpec> METRICS = metrics();

  private static Map<String, MetricSpec> metrics() {
    final Map<String, MetricSpec> m = new LinkedHashMap<>();
    m.put(DeskFactory.INTERNALISATION, new MetricSpec("hedge_slippage", 1, "USD per day"));
    m.put(DeskFactory.AXES, new MetricSpec("inventory_half_life_s", -1, "seconds, median per day"));
    m.put(DeskFactory.SIZING, new MetricSpec("capture_per_dv01", 1, "USD per USD of client DV01 (bp)"));
    m.put(DeskFactory.TIERING, new MetricSpec("adverse_selection", 1, "USD per day"));
    m.put(DeskFactory.TIMING, new MetricSpec("risk_adjusted_hedge_cost", 1, "USD per day"));
    m.put(DeskFactory.FUNDING, new MetricSpec("funding_net", 1, "USD per day"));
    m.put(DeskFactory.DESK_ALL, new MetricSpec("pnl_per_dv01", 1, "USD per USD of client DV01 (bp)"));
    return java.util.Collections.unmodifiableMap(m);
  }

  /** Secondary metrics reported beside the tested one. */
  public static final List<String> SECONDARY =
      List.of(
          "pnl", "spread_capture", "adverse_selection", "hedge_slippage", "hedges", "hedge_ratio",
          "mean_abs_net_dv01", "max_abs_net_dv01", "inventory_half_life_s", "capture_per_dv01", "pnl_per_dv01",
          "funding_net", "balance_sheet_charge", "limit_utilisation", "client_dv01_traded");

  /** The outcome: one ledger results map and verdict per treatment, and the journals used. */
  public record Outcome(
      Map<String, Map<String, Object>> resultsByTreatment,
      Map<String, String> verdictByTreatment,
      Map<String, Boolean> adoptedByTreatment,
      List<Map<String, Object>> journals) {

    public Outcome {
      resultsByTreatment = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(resultsByTreatment));
      verdictByTreatment = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(verdictByTreatment));
      adoptedByTreatment = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(adoptedByTreatment));
      journals = List.copyOf(journals);
    }
  }

  private final Path repoRoot;
  private final Map<String, Object> config;
  private final Path presetsFile;

  public DeskExperiment(final Path repoRoot, final Path configFile) {
    this.repoRoot = repoRoot;
    this.config = Yaml.loadMap(configFile);
    this.presetsFile = repoRoot.resolve(Yaml.string(config, "presets", configFile.toString()));
  }

  public Path repoRoot() {
    return repoRoot;
  }

  /** Seed of a day's journal: {@code day_seed_base} plus the epoch day. */
  public long daySeed(final LocalDate day) {
    return Yaml.integer(config, "day_seed_base", "desk experiment config") + day.toEpochDay();
  }

  /** The factory for a band point. */
  public DeskFactory factory(final Presets presets) {
    return new DeskFactory(config, presets, InstrumentSet.usdDefault());
  }

  /** The presets at a band point. */
  public Presets presets(final BandPoint point) {
    return BandScenario.presets(presetsFile, Yaml.map(config, "band", "desk experiment config"), point);
  }

  /** The back-test config of one journal at one band point, with the declared coupon carry. */
  public BacktestConfig backtestConfig(final Presets presets, final DeskFactory factory, final Path journal, final BandPoint point) {
    final BacktestConfig c =
        BacktestConfig.fromPresets(
            presets, List.of(journal), Yaml.integer(config, "outcome_seed", "desk experiment config"), point, null);
    return new BacktestConfig(
        c.journals(), c.instruments(), c.seed(), c.bandPoint(), c.outcomes(), c.strategyHitRate(), c.hedgeCost(),
        c.clob(), factory.carry(), c.calendar(), c.clientsPerTier(), c.quoteLatencyNanos(),
        c.clientDecisionNanos(), c.orderLatencyNanos(), c.markoutHorizonsNanos(), null);
  }

  /** Metric settings of a band point. */
  public static DeskMetrics.Settings settings(final DeskFactory f) {
    return new DeskMetrics.Settings(
        f.limitDv01(), f.positionLimitMm(), 2, f.gamma(), f.sigmaBpPerSqrtSecond(), f.bondBalanceSheetPerYear(),
        f.swapBalanceSheetPerYear());
  }

  /** Runs every treatment on {@code days}, using {@code workDir} for the journals. */
  public Outcome run(final List<LocalDate> days, final Path workDir) {
    final Map<String, Object> st = Yaml.map(config, "statistics", "desk experiment config");
    final int block = (int) Yaml.integer(st, "bootstrap_block_days", "statistics");
    final int resamples = (int) Yaml.integer(st, "bootstrap_resamples", "statistics");
    final long bootSeed = Yaml.integer(st, "bootstrap_seed", "statistics");
    final double level = Yaml.number(st, "level", "statistics");
    final double alpha = Yaml.number(st, "alpha", "statistics");

    final List<String> names = new ArrayList<>();
    names.add(DeskFactory.BASELINE);
    names.addAll(DeskFactory.TREATMENTS);
    final BandPoint[] points = BandPoint.values();
    final DeskMetrics[][][] m = new DeskMetrics[points.length][names.size()][days.size()];
    final List<Map<String, Object>> journals = new ArrayList<>();
    try {
      Files.createDirectories(workDir);
      for (int p = 0; p < points.length; p++) {
        final Presets presets = presets(points[p]);
        final DeskFactory factory = factory(presets);
        final DeskMetrics.Settings settings = settings(factory);
        final SyntheticDay generator = new SyntheticDay(SyntheticDay.Params.fromPresets(presets));
        final ClientTiering tieringAlone = factory.newTiering();
        final ClientTiering tieringAll = factory.newTiering();
        for (int d = 0; d < days.size(); d++) {
          final LocalDate day = days.get(d);
          final Path journal = workDir.resolve("desk-" + points[p].label() + "-" + day + ".raj");
          Files.deleteIfExists(journal);
          final SyntheticDay.Summary summary = generator.generate(day, daySeed(day), journal, null);
          final Map<String, Object> j = new LinkedHashMap<>();
          j.put("band_point", points[p].label());
          j.put("date", day.toString());
          j.put("seed", daySeed(day));
          j.put("sha256", Hashing.sha256File(journal));
          j.put("frames", summary.frames());
          j.put("rfqs", (long) summary.rfqs());
          journals.add(j);
          final BacktestConfig cfg = backtestConfig(presets, factory, journal, points[p]);
          for (int s = 0; s < names.size(); s++) {
            final String name = names.get(s);
            final ClientTiering tiering =
                DeskFactory.DESK_ALL.equals(name) ? tieringAll : tieringAlone;
            final BacktestResult r =
                new BacktestRunner(cfg)
                    .run(s == 0 ? factory.baseline() : factory.treatment(name, tiering));
            m[p][s][d] = DeskMetrics.of(r, cfg.instruments(), settings);
          }
          Files.delete(journal);
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return evaluate(names, days, m, block, resamples, bootSeed, level, alpha, journals);
  }

  static Outcome evaluate(
      final List<String> names,
      final List<LocalDate> days,
      final DeskMetrics[][][] m,
      final int block,
      final int resamples,
      final long bootSeed,
      final double level,
      final double alpha,
      final List<Map<String, Object>> journals) {
    final BandPoint[] points = BandPoint.values();
    final int treatments = names.size() - 1;
    final int tests = points.length * treatments;
    final double[] rawP = new double[tests];
    final double[][] diffs = new double[tests][];
    for (int p = 0; p < points.length; p++) {
      for (int t = 0; t < treatments; t++) {
        final MetricSpec spec = METRICS.get(names.get(t + 1));
        final double[] diff = new double[days.size()];
        for (int d = 0; d < diff.length; d++) {
          diff[d] = spec.orientation() * (m[p][t + 1][d].get(spec.metric()) - m[p][0][d].get(spec.metric()));
        }
        final int k = p * treatments + t;
        diffs[k] = diff;
        rawP[k] = pairedP(diff);
      }
    }
    final double[] holm = Stats.holm(rawP);
    final Map<String, Map<String, Object>> results = new LinkedHashMap<>();
    final Map<String, String> verdicts = new LinkedHashMap<>();
    final Map<String, Boolean> adopted = new LinkedHashMap<>();
    final List<String> dayList = new ArrayList<>();
    for (final LocalDate d : days) {
      dayList.add(d.toString());
    }
    for (int t = 0; t < treatments; t++) {
      final String name = names.get(t + 1);
      final MetricSpec spec = METRICS.get(name);
      final Map<String, Object> res = new LinkedHashMap<>();
      res.put("treatment", name);
      res.put("baseline", names.get(0));
      res.put("metric", spec.metric());
      res.put("metric_unit", spec.unit());
      res.put("orientation", spec.orientation() > 0 ? "larger is better" : "smaller is better");
      res.put("days", dayList);
      final Map<String, Object> band = new LinkedHashMap<>();
      boolean allImproved = true;
      boolean guards = true;
      for (int p = 0; p < points.length; p++) {
        final int k = p * treatments + t;
        final Stats.Interval ci = Stats.blockBootstrapMeanCi(diffs[k], block, resamples, level, bootSeed);
        final double[] pnlDiff = new double[days.size()];
        int breaches = 0;
        int newBreaches = 0;
        for (int d = 0; d < pnlDiff.length; d++) {
          pnlDiff[d] = m[p][t + 1][d].get("pnl") - m[p][0][d].get("pnl");
          final boolean b = m[p][t + 1][d].limitBreached();
          breaches += b ? 1 : 0;
          newBreaches += b && !m[p][0][d].limitBreached() ? 1 : 0;
        }
        final Stats.Interval pnlCi = Stats.blockBootstrapMeanCi(pnlDiff, block, resamples, level, bootSeed);
        final String v;
        if (holm[k] < alpha && ci.lower() > 0) {
          v = "improved";
        } else if (holm[k] < alpha && ci.upper() < 0) {
          v = "worse";
        } else {
          v = "inconclusive";
        }
        final boolean pnlGuard = !(pnlCi.upper() < 0);
        allImproved &= "improved".equals(v);
        guards &= pnlGuard && newBreaches == 0;
        final Map<String, Object> c = new LinkedHashMap<>();
        c.put("baseline_mean", round(mean(m[p][0], spec.metric()), 6));
        c.put("treatment_mean", round(mean(m[p][t + 1], spec.metric()), 6));
        c.put("difference_mean", round(ci.estimate() * spec.orientation(), 6));
        c.put("improvement_mean", round(ci.estimate(), 6));
        c.put("improvement_ci_low", round(ci.lower(), 6));
        c.put("improvement_ci_high", round(ci.upper(), 6));
        c.put("paired_p", round(rawP[k], 8));
        c.put("holm_p", round(holm[k], 8));
        c.put("verdict", v);
        c.put("pnl_difference_mean", round(pnlCi.estimate(), 2));
        c.put("pnl_difference_ci_low", round(pnlCi.lower(), 2));
        c.put("pnl_difference_ci_high", round(pnlCi.upper(), 2));
        c.put("pnl_guard", pnlGuard ? "pass" : "fail");
        c.put("limit_breach_days", (long) breaches);
        c.put("new_limit_breach_days", (long) newBreaches);
        final Map<String, Object> secondary = new LinkedHashMap<>();
        for (final String key : SECONDARY) {
          final Map<String, Object> pair = new LinkedHashMap<>();
          pair.put("baseline", round(mean(m[p][0], key), 6));
          pair.put("treatment", round(mean(m[p][t + 1], key), 6));
          secondary.put(key, pair);
        }
        c.put("secondary_means", secondary);
        band.put(points[p].label(), c);
      }
      res.put("band", band);
      res.put(
          "statistics",
          Map.of(
              "test", "paired t-test of the daily difference, two-sided",
              "multiple_comparison", "holm over " + tests + " tests (7 treatments x 3 band points)",
              "bootstrap_block_days", (long) block,
              "bootstrap_resamples", (long) resamples,
              "bootstrap_seed", bootSeed,
              "level", level,
              "alpha", alpha));
      final boolean adopt = allImproved && guards;
      res.put("guards", guards ? "pass" : "fail");
      res.put("adopted_as_default", adopt);
      results.put(name, res);
      verdicts.put(name, allImproved ? "supported" : "not supported");
      adopted.put(name, adopt);
    }
    return new Outcome(results, verdicts, adopted, journals);
  }

  /** Two-sided p of a paired t-test of mean(diff) = 0; 1 when every difference is zero. */
  static double pairedP(final double[] diff) {
    final int n = diff.length;
    final double mean = Stats.mean(diff);
    final double var = Stats.variance(diff);
    if (!(var > 0)) {
      return Math.abs(mean) > 0 ? 0.0 : 1.0;
    }
    final double t = mean / Math.sqrt(var / n);
    return Stats.studentTwoSidedP(t, n - 1);
  }

  private static double mean(final DeskMetrics[] days, final String key) {
    double s = 0;
    for (final DeskMetrics d : days) {
      s += d.get(key);
    }
    return s / days.length;
  }

  private static double round(final double v, final int digits) {
    final double f = Math.pow(10, digits);
    return Math.round(v * f) / f;
  }
}
