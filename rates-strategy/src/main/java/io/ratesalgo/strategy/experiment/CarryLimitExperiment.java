package io.ratesalgo.strategy.experiment;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.RiskCarry;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.prereg.PreRegistration;
import io.ratesalgo.backtest.prereg.PreRegistrationException;
import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.strategy.CarryLimitHedgeBudget;
import io.ratesalgo.strategy.DeskQuoter;
import io.ratesalgo.strategy.report.DeskMetrics;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The carry-limit experiment V1C-001: the desk default (netting, sliced hedges) with a limit on how
 * long net DV01 above a floor may stay unhedged ({@link CarryLimitHedgeBudget}), against the desk
 * default without it.
 *
 * <pre>
 *   CarryLimitExperiment --prereg data/preregs/V1C-001.yaml [--scope fitting|evaluation|holdout]
 *                        [--grid FLOOR:MAXCARRY_S[:QUIET_S],...] [--repo .] [--work target/carry-limit]
 *                        [--ledger data/ledger/ledger.jsonl] [--commit SHA] [--dirty true|false]
 * </pre>
 *
 * {@code fitting} runs the fitting period for every grid point, prints means and records nothing;
 * it is how the parameters in the config were chosen. {@code evaluation} and {@code holdout} run
 * the config's parameters once and append one ledger record whatever the verdict; the holdout is
 * refused once opened. Statistics are those of {@link HedgeExperiment}: a paired t-test per band
 * point, Holm over the 3 points, a moving-block bootstrap, a P&amp;L guard and a new-limit-breach
 * guard. Carry times ({@link RiskCarry}) are reported beside the metric.
 */
public final class CarryLimitExperiment {

  public static final String BASELINE = HedgeExperiment.DESK_DEFAULT;
  public static final String TREATMENT = "carry_limit";
  public static final String METRIC = "risk_adjusted_shortfall";
  public static final List<String> SECONDARY =
      List.of("pnl", "hedge_shortfall", "risk_charge", "hedges", "mean_abs_net_dv01", "max_abs_net_dv01",
          "carry_p50_s", "carry_p90_s", "unhedged_share");
  private static final String WHERE = "carry limit config";

  /** The rule's parameters. */
  public record Params(double floorDv01, double retainDv01, long maxCarryNanos, long quietNanos) {
    /** The V1C-001 rule: no quiet gap. */
    public Params(final double floorDv01, final double retainDv01, final long maxCarryNanos) {
      this(floorDv01, retainDv01, maxCarryNanos, 0L);
    }

    public String label() {
      return "floor " + Math.round(floorDv01) + " max " + maxCarryNanos / 1_000_000_000L + "s"
          + (quietNanos > 0 ? " quiet " + quietNanos / 1_000_000_000L + "s" : "");
    }
  }

  private final DeskExperiment desk;
  private final Map<String, Object> config;
  private final Path configPath;

  public CarryLimitExperiment(final Path repo, final Path configFile) {
    this.desk = new DeskExperiment(repo, configFile);
    this.config = Yaml.loadMap(configFile);
    this.configPath = configFile;
  }

  /** The parameters in the config. */
  public Params configured() {
    final Map<String, Object> c = Yaml.map(config, TREATMENT, WHERE);
    return new Params(
        Yaml.number(c, "floor_dv01", WHERE), Yaml.number(c, "retain_dv01", WHERE),
        Math.round(Yaml.number(c, "max_carry_s", WHERE) * 1e9),
        c.containsKey("quiet_s") ? Math.round(Yaml.number(c, "quiet_s", WHERE) * 1e9) : 0L);
  }

  /** The treatment: the desk default with its budget wrapped in the carry limit. */
  public static DeskQuoter treatment(final DeskFactory factory, final Params p) {
    final DeskQuoter.Features b = factory.nettingOnly().features();
    return new DeskQuoter(
        TREATMENT,
        new DeskQuoter.Features(
            b.ladder(), b.spread(), new CarryLimitHedgeBudget(b.budget(), p.floorDv01(), p.retainDv01(), p.maxCarryNanos(), p.quietNanos()),
            b.timing(), b.axes(), b.sizer(), b.tiering(), b.penalty(), b.markoutHorizonNanos()),
        DeskFactory.ADOPTED_SLICING);
  }

  /** Session metrics of every variant: [point][variant][day]; variant 0 is the baseline. */
  public HedgeExperiment.Session[][][] run(
      final List<LocalDate> days, final List<Params> variants, final Path work, final List<Map<String, Object>> journals) {
    final BandPoint[] points = BandPoint.values();
    final HedgeExperiment.Session[][][] m = new HedgeExperiment.Session[points.length][variants.size() + 1][days.size()];
    final HedgeExperiment hedge = new HedgeExperiment(desk.repoRoot(), configPath);
    try {
      Files.createDirectories(work);
      for (int p = 0; p < points.length; p++) {
        final Presets presets = desk.presets(points[p]);
        final DeskFactory factory = desk.factory(presets);
        final DeskMetrics.Settings settings = DeskExperiment.settings(factory);
        final SyntheticDay generator = new SyntheticDay(SyntheticDay.Params.fromPresets(presets));
        for (int d = 0; d < days.size(); d++) {
          final LocalDate day = days.get(d);
          final Path journal = work.resolve("carry-" + points[p].label() + "-" + day + ".raj");
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
          final BacktestConfig cfg = hedge.backtestConfig(presets, factory, journal, points[p]);
          for (int v = 0; v <= variants.size(); v++) {
            final DeskQuoter q = v == 0 ? factory.deskDefault() : treatment(factory, variants.get(v - 1));
            final BacktestResult r = new BacktestRunner(cfg).run(q);
            m[p][v][d] = withCarry(HedgeExperiment.session(DeskMetrics.of(r, cfg.instruments(), settings), q, settings), r);
          }
          Files.delete(journal);
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return m;
  }

  static HedgeExperiment.Session withCarry(final HedgeExperiment.Session s, final BacktestResult r) {
    final RiskCarry rc = RiskCarry.of(r.fills());
    final Map<String, Double> v = new LinkedHashMap<>(s.values());
    // a session with no hedge at all carries everything to the close: count it at the session length
    final double session = (r.endNanos() - r.startNanos()) / 1e9;
    v.put("carry_p50_s", rc.carrySeconds().length == 0 ? session : rc.quantile(0.5));
    v.put("carry_p90_s", rc.carrySeconds().length == 0 ? session : rc.quantile(0.9));
    v.put("unhedged_share", rc.clientFills() == 0 ? 0.0 : (double) rc.unhedgedClientFills() / rc.clientFills());
    return new HedgeExperiment.Session(v, s.limitBreached());
  }

  /** Paired evaluation of variant 1 against variant 0. */
  public Map<String, Object> evaluate(final HedgeExperiment.Session[][][] m, final List<LocalDate> days, final Params p) {
    final Map<String, Object> st = Yaml.map(config, "statistics", WHERE);
    final int block = (int) Yaml.integer(st, "bootstrap_block_days", WHERE);
    final int resamples = (int) Yaml.integer(st, "bootstrap_resamples", WHERE);
    final long seed = Yaml.integer(st, "bootstrap_seed", WHERE);
    final double level = Yaml.number(st, "level", WHERE);
    final double alpha = Yaml.number(st, "alpha", WHERE);
    final BandPoint[] points = BandPoint.values();
    final double[] rawP = new double[points.length];
    final double[][] diffs = new double[points.length][];
    for (int i = 0; i < points.length; i++) {
      diffs[i] = diff(m[i], METRIC, days.size());
      rawP[i] = DeskExperiment.pairedP(diffs[i]);
    }
    final double[] holm = Stats.holm(rawP);
    final Map<String, Object> band = new LinkedHashMap<>();
    boolean allImproved = true;
    boolean guards = true;
    for (int i = 0; i < points.length; i++) {
      final Stats.Interval ci = Stats.blockBootstrapMeanCi(diffs[i], block, resamples, level, seed);
      final Stats.Interval pnl = Stats.blockBootstrapMeanCi(diff(m[i], "pnl", days.size()), block, resamples, level, seed);
      int breaches = 0;
      int newBreaches = 0;
      for (int d = 0; d < days.size(); d++) {
        final boolean b = m[i][1][d].limitBreached();
        breaches += b ? 1 : 0;
        newBreaches += b && !m[i][0][d].limitBreached() ? 1 : 0;
      }
      final String v =
          holm[i] < alpha && ci.lower() > 0 ? "improved" : holm[i] < alpha && ci.upper() < 0 ? "worse" : "inconclusive";
      final boolean pnlGuard = !(pnl.upper() < 0);
      final Map<String, Object> c = new LinkedHashMap<>();
      c.put("baseline_mean", round(mean(m[i][0], METRIC), 2));
      c.put("treatment_mean", round(mean(m[i][1], METRIC), 2));
      c.put("improvement_mean", round(ci.estimate(), 2));
      c.put("improvement_ci_low", round(ci.lower(), 2));
      c.put("improvement_ci_high", round(ci.upper(), 2));
      c.put("paired_p", round(rawP[i], 8));
      c.put("holm_p", round(holm[i], 8));
      c.put("verdict", v);
      c.put("pnl_difference_mean", round(pnl.estimate(), 2));
      c.put("pnl_difference_ci_low", round(pnl.lower(), 2));
      c.put("pnl_difference_ci_high", round(pnl.upper(), 2));
      c.put("pnl_guard", pnlGuard ? "pass" : "fail");
      c.put("limit_breach_days", (long) breaches);
      c.put("new_limit_breach_days", (long) newBreaches);
      final Map<String, Object> secondary = new LinkedHashMap<>();
      for (final String key : SECONDARY) {
        final Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("baseline", round(mean(m[i][0], key), 4));
        pair.put("treatment", round(mean(m[i][1], key), 4));
        secondary.put(key, pair);
      }
      c.put("secondary_means", secondary);
      band.put(points[i].label(), c);
      allImproved &= "improved".equals(v);
      guards &= pnlGuard && newBreaches == 0;
    }
    final Map<String, Object> res = new LinkedHashMap<>();
    res.put("treatment", TREATMENT);
    res.put("baseline", BASELINE);
    res.put("parameters", Map.of(
        "floor_dv01", p.floorDv01(), "retain_dv01", p.retainDv01(), "max_carry_s", p.maxCarryNanos() / 1e9,
        "quiet_s", p.quietNanos() / 1e9));
    res.put("metric", METRIC);
    res.put("metric_unit", "USD per synthetic session");
    res.put("orientation", "larger is better");
    res.put("days", days.stream().map(LocalDate::toString).toList());
    res.put("band", band);
    res.put("statistics", Map.of(
        "test", "paired t-test of the daily difference, two-sided",
        "multiple_comparison", "holm over 3 tests (3 band points)",
        "bootstrap_block_days", (long) block, "bootstrap_resamples", (long) resamples, "bootstrap_seed", seed,
        "level", level, "alpha", alpha));
    res.put("guards", guards ? "pass" : "fail");
    res.put("verdict", allImproved ? "supported" : "not supported");
    res.put("adopted_as_default", allImproved && guards);
    return res;
  }

  public static void main(final String[] args) {
    final int code = run(args, System.out);
    if (code != 0) {
      throw new IllegalStateException("carry limit experiment exited with code " + code);
    }
  }

  /** Runs the command; returns the exit code (2 for a refusal, nothing run). */
  public static int run(final String[] args, final PrintStream out) {
    final Map<String, String> opts = DeskExperimentCli.parse(args);
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final Path work = Path.of(opts.getOrDefault("work", "target/carry-limit")).toAbsolutePath();
    final Path ledgerFile = repo.resolve(opts.getOrDefault("ledger", "data/ledger/ledger.jsonl"));
    final String scope = opts.getOrDefault("scope", "evaluation");
    if (!List.of("evaluation", "holdout", "fitting").contains(scope)) {
      out.println("refused: --scope must be evaluation, holdout or fitting");
      return 2;
    }
    if (!opts.containsKey("prereg")) {
      out.println("refused: --prereg is required");
      return 2;
    }
    final PreRegistration prereg;
    try {
      final PreRegistration r = PreRegistration.load(repo.resolve(opts.get("prereg")));
      prereg = "fitting".equals(scope) ? r : r.requireValid(repo);
    } catch (final PreRegistrationException e) {
      out.println("refused:");
      e.problems().forEach(p -> out.println("  " + p));
      return 2;
    }
    final Ledger ledger = new Ledger(ledgerFile);
    if ("holdout".equals(scope) && ledger.looks(prereg.id(), scope) > 0) {
      out.println("refused: the holdout of " + prereg.id() + " has already been opened once");
      return 2;
    }
    final Path config = repo.resolve(prereg.config());
    final CarryLimitExperiment exp = new CarryLimitExperiment(repo, config);
    final List<Map<String, Object>> journals = new ArrayList<>();
    if ("fitting".equals(scope)) {
      final List<Params> grid = new ArrayList<>();
      final Params base = exp.configured();
      for (final String g : opts.getOrDefault("grid", "50000:300,50000:900,50000:1800,100000:300,100000:900,100000:1800").split(",", -1)) {
        final String[] fs = g.trim().split(":", -1);
        grid.add(new Params(Double.parseDouble(fs[0]), base.retainDv01(), Math.round(Double.parseDouble(fs[1]) * 1e9),
            fs.length > 2 ? Math.round(Double.parseDouble(fs[2]) * 1e9) : 0L));
      }
      final List<LocalDate> days = prereg.fittingPeriod().businessDays();
      final HedgeExperiment.Session[][][] m = exp.run(days, grid, work, journals);
      final BandPoint[] points = BandPoint.values();
      for (int p = 0; p < points.length; p++) {
        out.println(points[p].label());
        out.printf("  %-26s %12s %12s %12s %8s %10s %10s %9s%n", "variant", METRIC, "pnl", "risk_charge", "hedges",
            "carry50", "carry90", "unhedged");
        for (int v = 0; v <= grid.size(); v++) {
          out.printf("  %-26s %12.0f %12.0f %12.0f %8.2f %10.1f %10.1f %9.3f%n",
              v == 0 ? BASELINE : grid.get(v - 1).label(), mean(m[p][v], METRIC), mean(m[p][v], "pnl"),
              mean(m[p][v], "risk_charge"), mean(m[p][v], "hedges"), mean(m[p][v], "carry_p50_s"),
              mean(m[p][v], "carry_p90_s"), mean(m[p][v], "unhedged_share"));
        }
      }
      return 0;
    }
    final List<LocalDate> days = "holdout".equals(scope) ? prereg.holdout() : prereg.period().businessDays();
    final Params p = exp.configured();
    final Map<String, Object> results = exp.evaluate(exp.run(days, List.of(p), work, journals), days, p);
    final StringBuilder concat = new StringBuilder();
    for (final Map<String, Object> j : journals) {
      concat.append(j.get("sha256")).append('\n');
    }
    final Map<String, Object> record = new LinkedHashMap<>();
    record.put("prereg_id", prereg.id());
    record.put("prereg_sha256", prereg.sha256());
    record.put("config_sha256", prereg.configSha256());
    record.put("journal_sha256", Hashing.sha256Text(concat.toString()));
    record.put("journals", journals);
    record.put("code_commit", opts.getOrDefault("commit", "unknown"));
    record.put("code_dirty", Boolean.parseBoolean(opts.getOrDefault("dirty", "false")));
    record.put("scope", scope);
    record.put("look", ledger.looks(prereg.id(), scope) + 1);
    record.put("recorded_utc", Instant.ofEpochSecond(SystemClock.INSTANCE.epochNanos() / 1_000_000_000L).toString());
    record.put("results", results);
    record.put("verdict", results.get("verdict"));
    ledger.append(record);
    out.println(Json.write(results));
    out.println(prereg.id() + " " + TREATMENT + " " + scope + ": " + results.get("verdict")
        + (Boolean.TRUE.equals(results.get("adopted_as_default")) ? ", adopted" : ", not adopted"));
    out.println("recorded in " + DeskExperimentCli.display(repo, ledgerFile));
    return 0;
  }

  private static double[] diff(final HedgeExperiment.Session[][] m, final String metric, final int n) {
    final double[] d = new double[n];
    for (int i = 0; i < n; i++) {
      d[i] = m[1][i].get(metric) - m[0][i].get(metric);
    }
    return d;
  }

  private static double mean(final HedgeExperiment.Session[] days, final String key) {
    return Arrays.stream(days).mapToDouble(s -> s.get(key)).average().orElse(Double.NaN);
  }

  private static double round(final double v, final int digits) {
    final double f = Math.pow(10, digits);
    return Math.round(v * f) / f;
  }
}
