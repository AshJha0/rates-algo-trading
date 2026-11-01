package io.ratesalgo.strategy.experiment;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Preset;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.strategy.CostAwareHedgeBudget;
import io.ratesalgo.strategy.DeskQuoter;
import io.ratesalgo.strategy.HedgeSlicing;
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
 * The hedge execution experiments of audit items 1 and 10 (V1H-001, V1H-002; DR-V1H-01 to
 * DR-V1H-03), run in one pass over the same journals with one Holm family of 6 tests.
 *
 * <ul>
 *   <li>{@value #SLICED} against {@value #DESK_DEFAULT} (V1H-001): the adopted desk default
 *       (internalisation, DR-R2B-07) with the residual hedged in depth-sized children, passive
 *       first with escalation ({@link HedgeSlicing}), against the same budget sending one market
 *       order. Metric {@code hedge_shortfall}.
 *   <li>{@value #ADAPTIVE_TRIGGER} against {@value #BASELINE} (V1H-002): the cost-aware trigger
 *       ({@link CostAwareHedgeBudget}) against the fixed 100,000 DV01 trigger, both hedging in one
 *       market order. Metric {@code risk_adjusted_shortfall}.
 * </ul>
 *
 * <p>Every run fills market hedges by the depth walk of audit fix package D ({@code
 * HedgeCostModel.withDepthWalk(true)}) and resting hedges by the queue model. Metrics added to
 * {@link DeskMetrics}:
 *
 * <pre>
 *   hedge_shortfall          = sum over hedge fills of qty * (decision mid - fill price) - hedge fees     (currency, larger is better)
 *   risk_adjusted_shortfall  = hedge_shortfall - gamma / 2 * sigma^2 * integral of net DV01^2 dt
 * </pre>
 *
 * The decision mid is the future's mid when the order (or the passive child it escalates) was
 * decided, so the shortfall carries the price drift while a passive child waits and the selection
 * of which children fill passively. Statistics and guards are those of {@link DeskExperiment}:
 * paired t-test, Holm, moving-block bootstrap, a P&amp;L guard and a new-limit-breach guard; the
 * sliced treatment also has a residual-risk guard (the bootstrap interval of the paired
 * difference in {@code risk_adjusted_shortfall} not entirely below zero).
 */
public final class HedgeExperiment {

  public static final String BASELINE = DeskFactory.BASELINE;
  public static final String DESK_DEFAULT = DeskFactory.DESK_DEFAULT;
  public static final String SLICED = "sliced";
  public static final String ADAPTIVE_TRIGGER = "adaptive_trigger";

  /** A treatment, the strategy it is compared with, its metric and its extra guard metric (or null). */
  public record Spec(String treatment, String baseline, String metric, String riskGuardMetric) {}

  public static final List<Spec> SPECS =
      List.of(
          new Spec(SLICED, DESK_DEFAULT, "hedge_shortfall", "risk_adjusted_shortfall"),
          new Spec(ADAPTIVE_TRIGGER, BASELINE, "risk_adjusted_shortfall", null));

  public static final List<String> TREATMENTS = List.of(SLICED, ADAPTIVE_TRIGGER);

  public static final List<String> SECONDARY =
      List.of(
          "pnl", "hedge_shortfall", "risk_adjusted_shortfall", "hedge_slippage", "risk_charge", "hedges",
          "hedge_ratio", "passive_fills", "escalations", "mean_abs_net_dv01", "max_abs_net_dv01",
          "limit_utilisation", "client_dv01_traded");

  private static final String WHERE = "hedge experiment config";

  /** Outcome: one results map, verdict and adoption flag per treatment, and the journals used. */
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

  /** One session's metrics: the desk metrics plus the shortfall metrics. */
  record Session(Map<String, Double> values, boolean limitBreached) {
    double get(final String key) {
      final Double v = values.get(key);
      if (v == null) {
        throw new IllegalArgumentException("unknown metric " + key);
      }
      return v;
    }
  }

  private final DeskExperiment desk;
  private final Map<String, Object> config;

  public HedgeExperiment(final Path repoRoot, final Path configFile) {
    this.desk = new DeskExperiment(repoRoot, configFile);
    this.config = Yaml.loadMap(configFile);
  }

  private double num(final String section, final String key) {
    return Yaml.number(Yaml.map(config, section, WHERE), key, WHERE + "." + section);
  }

  private static double perBp(final Instrument ty) {
    return Math.abs(ty.priceChangePerBp());
  }

  /** The slicing of the {@value #SLICED} treatment. */
  public HedgeSlicing slicing() {
    return new HedgeSlicing(
        num(SLICED, "depth_fraction"),
        Math.round(num(SLICED, "min_child_lots")),
        Math.round(num(SLICED, "passive_wait_s") * 1e9),
        num(SLICED, "urgent_dv01"));
  }

  /** The cost-aware budget of the {@value #ADAPTIVE_TRIGGER} treatment at a band point. */
  public CostAwareHedgeBudget adaptiveBudget(final Presets presets, final DeskFactory factory) {
    final Instrument ty = InstrumentSet.usdDefault().get(InstrumentSet.TY_FUTURE);
    final Preset market = presets.get("usd_synthetic_market");
    final Preset hedge = presets.get("usd_hedge_cost");
    final double tickBp = ty.tickMantissa() / 1e9 / perBp(ty);
    final double perContract = Math.abs(ty.dv01PerUnit()) * ty.lotSize();
    final double depthDv01 =
        market.value("book_depth_median_mm") * InstrumentSet.MILLION * Math.abs(ty.dv01PerUnit());
    return new CostAwareHedgeBudget(
        factory.gamma(),
        factory.sigmaBpPerSqrtSecond(),
        num(ADAPTIVE_TRIGGER, "horizon_s"),
        hedge.value("fee_per_lot") / perContract,
        (int) Math.floor(hedge.value("max_impact_ticks")),
        num(ADAPTIVE_TRIGGER, "cap_dv01"),
        0.5 * market.value("future_spread_ticks") * tickBp,
        tickBp,
        depthDv01);
  }

  /** A strategy by name at a band point. */
  public DeskQuoter strategy(final String name, final Presets presets, final DeskFactory factory) {
    return switch (name) {
      case BASELINE -> factory.baseline();
      case DESK_DEFAULT -> factory.deskDefault();
      case SLICED -> new DeskQuoter(SLICED, factory.deskDefault().features(), slicing());
      case ADAPTIVE_TRIGGER -> {
        final DeskQuoter.Features b = factory.baseline().features();
        yield new DeskQuoter(
            ADAPTIVE_TRIGGER,
            new DeskQuoter.Features(
                b.ladder(), b.spread(), adaptiveBudget(presets, factory), b.timing(), b.axes(), b.sizer(),
                b.tiering(), b.penalty(), b.markoutHorizonNanos()));
      }
      default -> throw new IllegalArgumentException("unknown strategy " + name);
    };
  }

  /** The back-test config: the desk experiment's, with depth-walk hedge fills. */
  public BacktestConfig backtestConfig(
      final Presets presets, final DeskFactory factory, final Path journal, final BandPoint point) {
    final BacktestConfig c = desk.backtestConfig(presets, factory, journal, point);
    return new BacktestConfig(
        c.journals(), c.instruments(), c.seed(), c.bandPoint(), c.outcomes(), c.strategyHitRate(),
        c.hedgeCost().withDepthWalk(true), c.clob(), c.carry(), c.calendar(), c.clientsPerTier(),
        c.quoteLatencyNanos(), c.clientDecisionNanos(), c.orderLatencyNanos(), c.markoutHorizonsNanos(), null);
  }

  static Session session(final DeskMetrics m, final DeskQuoter q, final DeskMetrics.Settings s) {
    final Map<String, Double> v = new LinkedHashMap<>(m.values());
    final double fees = m.get("hedge_cost") - m.get("hedge_slippage");
    final double shortfall = q.hedgeShortfall() - fees;
    v.put("hedge_shortfall", shortfall);
    v.put("risk_adjusted_shortfall", shortfall - m.get("risk_charge"));
    v.put("passive_fills", (double) q.passiveFills());
    v.put("escalations", (double) q.escalations());
    return new Session(v, m.limitBreached());
  }

  /** Runs every strategy on {@code days}; journals go to {@code workDir} and are deleted after use. */
  public Outcome run(final List<LocalDate> days, final Path workDir) {
    final Map<String, Object> st = Yaml.map(config, "statistics", WHERE);
    final List<String> names = List.of(BASELINE, DESK_DEFAULT, SLICED, ADAPTIVE_TRIGGER);
    final BandPoint[] points = BandPoint.values();
    final Session[][][] m = new Session[points.length][names.size()][days.size()];
    final List<Map<String, Object>> journals = new ArrayList<>();
    try {
      Files.createDirectories(workDir);
      for (int p = 0; p < points.length; p++) {
        final Presets presets = desk.presets(points[p]);
        final DeskFactory factory = desk.factory(presets);
        final DeskMetrics.Settings settings = DeskExperiment.settings(factory);
        final SyntheticDay generator = new SyntheticDay(SyntheticDay.Params.fromPresets(presets));
        for (int d = 0; d < days.size(); d++) {
          final LocalDate day = days.get(d);
          final Path journal = workDir.resolve("hedge-" + points[p].label() + "-" + day + ".raj");
          Files.deleteIfExists(journal);
          final SyntheticDay.Summary summary = generator.generate(day, desk.daySeed(day), journal, null);
          final Map<String, Object> j = new LinkedHashMap<>();
          j.put("band_point", points[p].label());
          j.put("date", day.toString());
          j.put("seed", desk.daySeed(day));
          j.put("sha256", Hashing.sha256File(journal));
          j.put("frames", summary.frames());
          j.put("rfqs", (long) summary.rfqs());
          journals.add(j);
          final BacktestConfig cfg = backtestConfig(presets, factory, journal, points[p]);
          for (int s = 0; s < names.size(); s++) {
            final DeskQuoter q = strategy(names.get(s), presets, factory);
            final BacktestResult r = new BacktestRunner(cfg).run(q);
            m[p][s][d] = session(DeskMetrics.of(r, cfg.instruments(), settings), q, settings);
          }
          Files.delete(journal);
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return evaluate(
        names, days, m,
        (int) Yaml.integer(st, "bootstrap_block_days", WHERE),
        (int) Yaml.integer(st, "bootstrap_resamples", WHERE),
        Yaml.integer(st, "bootstrap_seed", WHERE),
        Yaml.number(st, "level", WHERE),
        Yaml.number(st, "alpha", WHERE),
        journals);
  }

  static Outcome evaluate(
      final List<String> names,
      final List<LocalDate> days,
      final Session[][][] m,
      final int block,
      final int resamples,
      final long bootSeed,
      final double level,
      final double alpha,
      final List<Map<String, Object>> journals) {
    final BandPoint[] points = BandPoint.values();
    final int tests = points.length * SPECS.size();
    final double[] rawP = new double[tests];
    final double[][] diffs = new double[tests][];
    for (int p = 0; p < points.length; p++) {
      for (int t = 0; t < SPECS.size(); t++) {
        final Spec spec = SPECS.get(t);
        diffs[p * SPECS.size() + t] = diff(m[p], names, spec.treatment(), spec.baseline(), spec.metric(), days.size());
        rawP[p * SPECS.size() + t] = DeskExperiment.pairedP(diffs[p * SPECS.size() + t]);
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
    for (int t = 0; t < SPECS.size(); t++) {
      final Spec spec = SPECS.get(t);
      final int ti = names.indexOf(spec.treatment());
      final int bi = names.indexOf(spec.baseline());
      final Map<String, Object> res = new LinkedHashMap<>();
      res.put("treatment", spec.treatment());
      res.put("baseline", spec.baseline());
      res.put("metric", spec.metric());
      res.put("metric_unit", "USD per synthetic session");
      res.put("orientation", "larger is better");
      res.put("hedge_fill_rule", "depth walk (HedgeCostModel.withDepthWalk(true)); resting orders by queue model");
      res.put("days", dayList);
      final Map<String, Object> band = new LinkedHashMap<>();
      boolean allImproved = true;
      boolean guards = true;
      for (int p = 0; p < points.length; p++) {
        final int k = p * SPECS.size() + t;
        final Stats.Interval ci = Stats.blockBootstrapMeanCi(diffs[k], block, resamples, level, bootSeed);
        final double[] pnl = diff(m[p], names, spec.treatment(), spec.baseline(), "pnl", days.size());
        final Stats.Interval pnlCi = Stats.blockBootstrapMeanCi(pnl, block, resamples, level, bootSeed);
        int breaches = 0;
        int newBreaches = 0;
        for (int d = 0; d < days.size(); d++) {
          final boolean b = m[p][ti][d].limitBreached();
          breaches += b ? 1 : 0;
          newBreaches += b && !m[p][bi][d].limitBreached() ? 1 : 0;
        }
        final String v;
        if (holm[k] < alpha && ci.lower() > 0) {
          v = "improved";
        } else if (holm[k] < alpha && ci.upper() < 0) {
          v = "worse";
        } else {
          v = "inconclusive";
        }
        final boolean pnlGuard = !(pnlCi.upper() < 0);
        boolean riskGuard = true;
        final Map<String, Object> c = new LinkedHashMap<>();
        c.put("baseline_mean", round(mean(m[p][bi], spec.metric()), 2));
        c.put("treatment_mean", round(mean(m[p][ti], spec.metric()), 2));
        c.put("improvement_mean", round(ci.estimate(), 2));
        c.put("improvement_ci_low", round(ci.lower(), 2));
        c.put("improvement_ci_high", round(ci.upper(), 2));
        c.put("paired_p", round(rawP[k], 8));
        c.put("holm_p", round(holm[k], 8));
        c.put("verdict", v);
        if (spec.riskGuardMetric() != null) {
          final double[] r =
              diff(m[p], names, spec.treatment(), spec.baseline(), spec.riskGuardMetric(), days.size());
          final Stats.Interval rCi = Stats.blockBootstrapMeanCi(r, block, resamples, level, bootSeed);
          riskGuard = !(rCi.upper() < 0);
          c.put("risk_guard_metric", spec.riskGuardMetric());
          c.put("risk_guard_difference_mean", round(rCi.estimate(), 2));
          c.put("risk_guard_ci_low", round(rCi.lower(), 2));
          c.put("risk_guard_ci_high", round(rCi.upper(), 2));
          c.put("risk_guard", riskGuard ? "pass" : "fail");
        }
        c.put("pnl_difference_mean", round(pnlCi.estimate(), 2));
        c.put("pnl_difference_ci_low", round(pnlCi.lower(), 2));
        c.put("pnl_difference_ci_high", round(pnlCi.upper(), 2));
        c.put("pnl_guard", pnlGuard ? "pass" : "fail");
        c.put("limit_breach_days", (long) breaches);
        c.put("new_limit_breach_days", (long) newBreaches);
        final Map<String, Object> secondary = new LinkedHashMap<>();
        for (final String key : SECONDARY) {
          final Map<String, Object> pair = new LinkedHashMap<>();
          pair.put("baseline", round(mean(m[p][bi], key), 4));
          pair.put("treatment", round(mean(m[p][ti], key), 4));
          secondary.put(key, pair);
        }
        c.put("secondary_means", secondary);
        band.put(points[p].label(), c);
        allImproved &= "improved".equals(v);
        guards &= pnlGuard && riskGuard && newBreaches == 0;
      }
      res.put("band", band);
      res.put(
          "statistics",
          Map.of(
              "test", "paired t-test of the daily difference, two-sided",
              "multiple_comparison", "holm over " + tests + " tests (2 treatments x 3 band points)",
              "bootstrap_block_days", (long) block,
              "bootstrap_resamples", (long) resamples,
              "bootstrap_seed", bootSeed,
              "level", level,
              "alpha", alpha));
      final boolean adopt = allImproved && guards;
      res.put("guards", guards ? "pass" : "fail");
      res.put("adopted_as_default", adopt);
      results.put(spec.treatment(), res);
      verdicts.put(spec.treatment(), allImproved ? "supported" : "not supported");
      adopted.put(spec.treatment(), adopt);
    }
    return new Outcome(results, verdicts, adopted, journals);
  }

  private static double[] diff(
      final Session[][] m, final List<String> names, final String treatment, final String baseline,
      final String metric, final int n) {
    final int ti = names.indexOf(treatment);
    final int bi = names.indexOf(baseline);
    final double[] d = new double[n];
    for (int i = 0; i < n; i++) {
      d[i] = m[ti][i].get(metric) - m[bi][i].get(metric);
    }
    return d;
  }

  private static double mean(final Session[] days, final String key) {
    double s = 0;
    for (final Session d : days) {
      s += d.get(key);
    }
    return s / days.length;
  }

  private static double round(final double v, final int digits) {
    final double f = Math.pow(10, digits);
    return Math.round(v * f) / f;
  }
}
