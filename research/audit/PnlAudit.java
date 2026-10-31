// P&L audit harness: runs pre-registered engine variants on seeded synthetic days with the
// existing back-tester (rates-backtest) and appends one ledger row per pre-registration.
//
// Run with research/audit/run_audit.sh, which builds rates-backtest and launches this file in
// Java source-file mode against the worktree's own classes:
//
//   java -cp <classpath> research/audit/PnlAudit.java --prereg research/prereg/AUDIT-01.yaml
//        [--repo .] [--ledger data/ledger/ledger.jsonl] [--work <dir>] [--days <start>..<end>]
//        [--out research/audit/results/AUDIT-01.json]
//
// --days replaces the evaluation period for a pilot run (only dates inside the fitting period
// are accepted, so a pilot never reads an evaluation day); a pilot writes to the ledger given
// with --ledger, which must then not be the repository ledger.
//
// The pre-registration is validated with the back-tester's own PreRegistration rules, the
// config hash is checked, and an evaluation look that already exists in the ledger is refused.
// Everything here only reads the back-tester's public API; nothing in the engine is changed.

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.FillRecord;
import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.CompetitorModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.model.Preset;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.prereg.PreRegistration;
import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.strategy.ModelSpreadStrategy;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PnlAudit {

  private PnlAudit() {}

  public static void main(final String[] args) throws IOException {
    final Map<String, String> opts = new LinkedHashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("expected --name value pairs, got " + args[i]);
      }
      opts.put(args[i].substring(2), args[i + 1]);
    }
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final Path preregFile = repo.resolve(require(opts, "prereg"));
    final Path ledgerFile = repo.resolve(opts.getOrDefault("ledger", "data/ledger/ledger.jsonl"));
    final Path work =
        Path.of(opts.getOrDefault("work", System.getProperty("java.io.tmpdir") + "/pnl-audit"))
            .toAbsolutePath();
    final PreRegistration prereg = PreRegistration.load(preregFile).requireValid(repo);
    final boolean pilot = opts.containsKey("days");
    final Path repoLedger = repo.resolve("data/ledger/ledger.jsonl").normalize();
    if (pilot && ledgerFile.normalize().equals(repoLedger)) {
      throw new IllegalArgumentException("a pilot run must not write to the repository ledger");
    }
    final List<LocalDate> days;
    if (pilot) {
      final String[] r = opts.get("days").split("\\.\\.");
      days = new PreRegistration.DateRange(LocalDate.parse(r[0]), LocalDate.parse(r[1])).businessDays();
      for (final LocalDate d : days) {
        if (!prereg.fittingPeriod().contains(d)) {
          throw new IllegalArgumentException("pilot day " + d + " is outside the fitting period");
        }
      }
    } else {
      days = prereg.period().businessDays();
    }
    final Ledger ledger = new Ledger(ledgerFile);
    final String scope = pilot ? "pilot" : "evaluation";
    final long previous = ledger.looks(prereg.id(), scope);
    if (!pilot && previous > 0) {
      System.out.println("refused: " + prereg.id() + " already has an evaluation record; an audit look is taken once");
      System.exit(2);
    }
    final Map<String, Object> config = Yaml.loadMap(repo.resolve(prereg.config()));
    final Audit audit = new Audit(repo, config, prereg.baseline(), prereg.treatments());
    final Audit.Outcome outcome = audit.run(days, work);

    final StringBuilder concat = new StringBuilder();
    for (final Map<String, Object> j : outcome.journals()) {
      concat.append(j.get("sha256")).append('\n');
    }
    final Map<String, Object> record = new LinkedHashMap<>();
    record.put("prereg_id", prereg.id());
    record.put("prereg_sha256", prereg.sha256());
    record.put("config_sha256", prereg.configSha256());
    record.put("journal_sha256", Hashing.sha256Text(concat.toString()));
    record.put("journals", outcome.journals());
    record.put("code_commit", opts.getOrDefault("commit", git(repo, "rev-parse", "HEAD")));
    record.put("code_dirty", !git(repo, "status", "--porcelain", "--untracked-files=no").isEmpty());
    record.put("harness", "research/audit/PnlAudit.java");
    record.put("scope", scope);
    record.put("look", previous + 1);
    record.put("recorded_utc", Instant.ofEpochSecond(Instant.now().getEpochSecond()).toString());
    record.put("results", outcome.results());
    record.put("verdict", outcome.verdict());
    ledger.append(record);
    if (opts.containsKey("out")) {
      final Path out = repo.resolve(opts.get("out"));
      Files.createDirectories(out.getParent());
      Files.writeString(out, Json.write(record) + "\n", StandardCharsets.UTF_8);
    }
    System.out.println(prereg.id() + " " + scope + " look " + (previous + 1) + ": " + outcome.verdict()
        + " (" + days.size() + " days); recorded in " + ledgerFile);
  }

  private static String require(final Map<String, String> opts, final String key) {
    final String v = opts.get(key);
    if (v == null) {
      throw new IllegalArgumentException("--" + key + " is required");
    }
    return v;
  }

  private static String git(final Path repo, final String... args) {
    final List<String> cmd = new ArrayList<>();
    cmd.add("git");
    cmd.addAll(List.of(args));
    try {
      final Process p = new ProcessBuilder(cmd).directory(repo.toFile()).start();
      try (InputStream in = p.getInputStream()) {
        final String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        return p.waitFor() == 0 ? s : "unknown";
      }
    } catch (final IOException e) {
      return "unknown";
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return "unknown";
    }
  }
}

/** Runs every variant of one pre-registration over the days and computes the statistics. */
final class Audit {

  record Outcome(String verdict, Map<String, Object> results, List<Map<String, Object>> journals) {}

  private final Map<String, Object> config;
  private final Presets presets;
  private final String baseline;
  private final List<String> treatments;
  private final Map<String, Variant> variants = new LinkedHashMap<>();
  private final Pricing pricing;

  Audit(final Path repo, final Map<String, Object> config, final String baseline, final List<String> treatments) {
    this.config = config;
    this.presets = Presets.load(repo.resolve(Yaml.string(config, "presets", "audit config")));
    this.baseline = baseline;
    this.treatments = treatments;
    this.pricing = new Pricing(Yaml.map(config, "pricing", "audit config"));
    final Map<String, Object> all = Yaml.map(config, "variants", "audit config");
    final List<String> names = new ArrayList<>();
    names.add(baseline);
    names.addAll(treatments);
    for (final String n : names) {
      variants.put(n, new Variant(n, Yaml.map(all, n, "variants")));
    }
  }

  Outcome run(final List<LocalDate> days, final Path work) throws IOException {
    final Map<String, Object> st = Yaml.map(config, "statistics", "audit config");
    final int block = (int) Yaml.integer(st, "bootstrap_block_days", "statistics");
    final int resamples = (int) Yaml.integer(st, "bootstrap_resamples", "statistics");
    final long bootSeed = Yaml.integer(st, "bootstrap_seed", "statistics");
    final double level = Yaml.number(st, "level", "statistics");
    final double alpha = Yaml.number(st, "alpha", "statistics");
    final long seedBase = Yaml.integer(config, "day_seed_base", "audit config");
    final long outcomeSeed = Yaml.integer(config, "outcome_seed", "audit config");

    final List<String> names = new ArrayList<>(variants.keySet());
    final BandPoint[] points = BandPoint.values();
    final DayMetrics[][][] metrics = new DayMetrics[points.length][names.size()][days.size()];
    final List<Map<String, Object>> journals = new ArrayList<>();
    final SyntheticDay generator = new SyntheticDay(SyntheticDay.Params.fromPresets(presets));
    int replicaMismatches = 0;
    int replicaChecks = 0;
    Files.createDirectories(work);
    for (int d = 0; d < days.size(); d++) {
      final LocalDate day = days.get(d);
      final Path journal = work.resolve("audit-" + day + ".raj");
      final Path truth = work.resolve("audit-" + day + ".truth.csv");
      Files.deleteIfExists(journal);
      final long seed = seedBase + day.toEpochDay();
      final SyntheticDay.Summary summary = generator.generate(day, seed, journal, truth);
      final Set<Long> informed = readInformed(truth);
      final Map<String, Object> j = new LinkedHashMap<>();
      j.put("date", day.toString());
      j.put("seed", seed);
      j.put("sha256", Hashing.sha256File(journal));
      j.put("frames", summary.frames());
      j.put("rfqs", (long) summary.rfqs());
      j.put("informed_rfqs", (long) summary.informedRfqs());
      journals.add(j);
      for (int p = 0; p < points.length; p++) {
        final BacktestConfig cfg = BacktestConfig.fromPresets(presets, List.of(journal), outcomeSeed, points[p], null);
        for (int s = 0; s < names.size(); s++) {
          final Variant v = variants.get(names.get(s));
          final BacktestResult r = new BacktestRunner(cfg).run(new AuditStrategy(v, pricing, presets, points[p], informed));
          metrics[p][s][d] = DayMetrics.of(r, cfg.instruments(), v);
          if (v.replicaOf() != null) {
            // the variant must reproduce the named back-tester strategy to the cent
            final BacktestResult ref = new BacktestRunner(cfg).run(pricing.modelSpread(presets, v));
            replicaChecks++;
            if (Prices.cents(ref.total().positionPnl()) != metrics[p][s][d].pnl) {
              replicaMismatches++;
            }
          }
        }
      }
      Files.delete(journal);
      Files.delete(truth);
      System.out.println("  " + day + " done");
    }

    final int tests = points.length * treatments.size();
    final double[] rawP = new double[tests];
    final Stats.Welch[] welch = new Stats.Welch[tests];
    final Stats.Interval[] diffCi = new Stats.Interval[tests];
    for (int p = 0; p < points.length; p++) {
      for (int t = 0; t < treatments.size(); t++) {
        final int k = p * treatments.size() + t;
        final double[] treat = pnl(metrics[p][t + 1]);
        final double[] base = pnl(metrics[p][0]);
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
        final double[] x = pnl(metrics[p][s]);
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
        m.put("daily_std", Prices.cents(Math.sqrt(Stats.variance(x))));
        m.putAll(DayMetrics.summary(metrics[p][s]));
        strategies.put(names.get(s), m);
      }
      band.put("strategies", strategies);
      final double baseDv01 = DayMetrics.meanClientDv01(metrics[p][0]);
      final double baseNotional = DayMetrics.meanNotional(metrics[p][0]);
      final Map<String, Object> comparisons = new LinkedHashMap<>();
      for (int t = 0; t < treatments.size(); t++) {
        final int k = p * treatments.size() + t;
        final Map<String, Object> c = new LinkedHashMap<>();
        c.put("mean_difference", Prices.cents(welch[k].difference()));
        c.put("paired_ci_low", Prices.cents(diffCi[k].lower()));
        c.put("paired_ci_high", Prices.cents(diffCi[k].upper()));
        // normalised by the baseline's mean client DV01 and notional traded per day
        c.put("per_mm_dv01_traded", round(welch[k].difference() / (baseDv01 / 1e6), 2));
        c.put("per_mm_dv01_ci_low", round(diffCi[k].lower() / (baseDv01 / 1e6), 2));
        c.put("per_mm_dv01_ci_high", round(diffCi[k].upper() / (baseDv01 / 1e6), 2));
        c.put("bp_of_notional", round(1e4 * welch[k].difference() / baseNotional, 6));
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
    results.put("replica_check", Map.of("checks", (long) replicaChecks, "mismatches", (long) replicaMismatches));
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

  private static double[] pnl(final DayMetrics[] days) {
    final double[] x = new double[days.length];
    for (int i = 0; i < x.length; i++) {
      x[i] = days[i].pnl;
    }
    return x;
  }

  private static Set<Long> readInformed(final Path truth) throws IOException {
    final Set<Long> out = new HashSet<>();
    final List<String> lines = Files.readAllLines(truth, StandardCharsets.UTF_8);
    for (int i = 1; i < lines.size(); i++) {
      final String[] f = lines.get(i).split(",");
      if (f.length == 4 && "1".equals(f[3])) {
        out.add(Long.parseLong(f[0]));
      }
    }
    return out;
  }

  static double round(final double v, final int digits) {
    final double f = Math.pow(10, digits);
    return Math.round(v * f) / f;
  }
}

/** Pricing inputs shared by every variant (the P5-001 model_spread estimates). */
final class Pricing {
  final double[] adverseBpByTier;
  final double swapHedgeCostBp;
  final double bondHedgeCostBp;
  final double maxBp;
  final double stepBp;

  Pricing(final Map<String, Object> m) {
    final List<Object> a = Yaml.asList(Yaml.require(m, "adverse_bp_by_tier", "pricing"), "pricing");
    adverseBpByTier = new double[a.size()];
    for (int i = 0; i < a.size(); i++) {
      adverseBpByTier[i] = Yaml.toDouble(a.get(i), "pricing.adverse_bp_by_tier");
    }
    swapHedgeCostBp = Yaml.number(m, "swap_hedge_cost_bp", "pricing");
    bondHedgeCostBp = Yaml.number(m, "bond_hedge_cost_bp", "pricing");
    maxBp = Yaml.number(m, "max_half_spread_bp", "pricing");
    stepBp = Yaml.number(m, "grid_step_bp", "pricing");
  }

  double adverse(final int tier) {
    final int i = Math.min(Math.max(tier, 1), adverseBpByTier.length) - 1;
    return adverseBpByTier[i];
  }

  double hedgeCost(final Instrument instrument) {
    return instrument.kind() == Instrument.Kind.SWAP ? swapHedgeCostBp : bondHedgeCostBp;
  }

  /** The back-tester's own ModelSpreadStrategy with these inputs and the variant's threshold. */
  ModelSpreadStrategy modelSpread(final Presets presets, final Variant v) {
    return new ModelSpreadStrategy(
        HitRateModel.fromPreset(presets.get(HitRateModel.PRESET), BandPoint.CENTRAL),
        CompetitorModel.fromPreset(presets.get(CompetitorModel.PRESET)),
        adverseBpByTier, swapHedgeCostBp, bondHedgeCostBp, maxBp, stepBp, v.hedgeThresholdDv01,
        (int) presets.get("usd_synthetic_market").value("clients_per_tier"));
  }
}

/** One engine variant, read from the audit config. */
final class Variant {
  final String name;
  final String spread;
  final double swapFixedBp;
  final double bondFixedBp;
  final double[] extraBpByTier;
  final Set<Integer> declineTiers = new HashSet<>();
  final boolean declineInformed;
  final List<Integer> guardEvents = new ArrayList<>();
  final double guardBeforeS;
  final double guardAfterS;
  final double skewBpPer100k;
  final String hedgeMode;
  final double hedgeThresholdDv01;
  private final String replicaOf;

  Variant(final String name, final Map<String, Object> m) {
    this.name = name;
    spread = Yaml.string(m, "spread", name);
    swapFixedBp = Yaml.number(m, "swap_half_spread_bp", 0.0);
    bondFixedBp = Yaml.number(m, "bond_half_spread_bp", 0.0);
    extraBpByTier = new double[] {0, 0, 0};
    if (m.get("extra_bp_by_tier") != null) {
      final List<Object> e = Yaml.asList(m.get("extra_bp_by_tier"), name);
      for (int i = 0; i < 3; i++) {
        extraBpByTier[i] = Yaml.toDouble(e.get(i), name + ".extra_bp_by_tier");
      }
    }
    if (m.get("decline_tiers") != null) {
      for (final Object o : Yaml.asList(m.get("decline_tiers"), name)) {
        declineTiers.add((int) Yaml.toDouble(o, name + ".decline_tiers"));
      }
    }
    declineInformed = Boolean.TRUE.equals(m.get("decline_informed"));
    if (m.get("guard_events") != null) {
      for (final Object o : Yaml.asList(m.get("guard_events"), name)) {
        guardEvents.add((int) Yaml.toDouble(o, name + ".guard_events"));
      }
    }
    guardBeforeS = Yaml.number(m, "guard_before_s", 0.0);
    guardAfterS = Yaml.number(m, "guard_after_s", 0.0);
    skewBpPer100k = Yaml.number(m, "skew_bp_per_100k_dv01", 0.0);
    hedgeMode = Yaml.string(m, "hedge_mode", name);
    hedgeThresholdDv01 = Yaml.number(m, "hedge_threshold_dv01", name);
    replicaOf = m.get("replica_of") == null ? null : String.valueOf(m.get("replica_of"));
    if (!List.of("model", "model_band", "cover_integrated", "fixed").contains(spread)) {
      throw new IllegalArgumentException(name + ": unknown spread mode " + spread);
    }
    if (!List.of("ty_market", "bucketed_market", "ty_passive").contains(hedgeMode)) {
      throw new IllegalArgumentException(name + ": unknown hedge mode " + hedgeMode);
    }
    if (replicaOf != null && !(spread.equals("model") && hedgeMode.equals("ty_market"))) {
      throw new IllegalArgumentException(name + ": only a model/ty_market variant can be a replica");
    }
  }

  String replicaOf() {
    return replicaOf;
  }
}

/**
 * The audited quoter. With spread "model" and hedge mode "ty_market" and no other option it
 * reproduces the back-tester's ModelSpreadStrategy decision for decision (checked on every run).
 */
final class AuditStrategy implements Strategy {

  private static final int[] FRONT = {InstrumentSet.SOFR_2Y, InstrumentSet.SOFR_5Y, InstrumentSet.UST_2Y};
  private static final int[] BACK = {
    InstrumentSet.SOFR_10Y, InstrumentSet.SOFR_30Y, InstrumentSet.UST_10Y, InstrumentSet.TY_FUTURE
  };

  private final Variant v;
  private final Pricing pricing;
  private final ModelSpreadStrategy central;
  private final ModelSpreadStrategy atBand;
  private final HitRateModel centralHit;
  private final CompetitorModel competitors;
  private final int clientsPerTier;
  private final Set<Long> informed;
  private final boolean[] inFlight = new boolean[2];
  private final Map<Long, Integer> orderBucket = new HashMap<>();
  private long[] guardStart = new long[0];
  private long[] guardEnd = new long[0];

  AuditStrategy(
      final Variant v, final Pricing pricing, final Presets presets, final BandPoint point, final Set<Long> informed) {
    this.v = v;
    this.pricing = pricing;
    this.informed = informed;
    this.clientsPerTier = (int) presets.get("usd_synthetic_market").value("clients_per_tier");
    this.competitors = CompetitorModel.fromPreset(presets.get(CompetitorModel.PRESET));
    this.centralHit = HitRateModel.fromPreset(presets.get(HitRateModel.PRESET), BandPoint.CENTRAL);
    this.central = pricing.modelSpread(presets, v);
    this.atBand =
        new ModelSpreadStrategy(
            HitRateModel.fromPreset(presets.get(HitRateModel.PRESET), point), competitors,
            pricing.adverseBpByTier, pricing.swapHedgeCostBp, pricing.bondHedgeCostBp, pricing.maxBp,
            pricing.stepBp, v.hedgeThresholdDv01, clientsPerTier);
  }

  @Override
  public String name() {
    return v.name;
  }

  @Override
  public void onStart(final StrategyContext ctx) {
    final long[] events = ctx.calendar().events();
    guardStart = new long[v.guardEvents.size()];
    guardEnd = new long[v.guardEvents.size()];
    for (int i = 0; i < guardStart.length; i++) {
      final long e = events[v.guardEvents.get(i)];
      guardStart[i] = e - Math.round(v.guardBeforeS * 1e9);
      guardEnd[i] = e + Math.round(v.guardAfterS * 1e9);
    }
  }

  @Override
  public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {
    final int id = rfq.instrumentId();
    if (!ctx.hasMid(id)) {
      return;
    }
    final int tier = 1 + (rfq.clientId() - 1) / clientsPerTier;
    if (v.declineTiers.contains(tier) || (v.declineInformed && informed.contains(rfq.rfqId()))) {
      return;
    }
    final long now = ctx.nowEpochNanos();
    for (int i = 0; i < guardStart.length; i++) {
      if (now >= guardStart[i] && now <= guardEnd[i]) {
        return;
      }
    }
    final Instrument instrument = ctx.instruments().get(id);
    final double size = (double) rfq.quantity() / InstrumentSet.MILLION;
    final boolean inWindow = ctx.calendar().inWindow(now);
    final int dealers = rfq.dealerCount();
    double h =
        switch (v.spread) {
          case "model" -> central.optimalHalfSpreadBp(instrument, size, tier, dealers, inWindow);
          case "model_band" -> atBand.optimalHalfSpreadBp(instrument, size, tier, dealers, inWindow);
          case "cover_integrated" -> coverIntegrated(instrument, size, tier, dealers, inWindow);
          default -> instrument.kind() == Instrument.Kind.SWAP ? v.swapFixedBp : v.bondFixedBp;
        };
    h += v.extraBpByTier[Math.min(Math.max(tier, 1), 3) - 1];
    if (Double.isNaN(h) || h < 0) {
      return;
    }
    double mid = ctx.mid(id);
    if (v.skewBpPer100k != 0.0) {
      // shift the quote centre so that the side which reduces |net DV01| is the cheaper one
      final double sign = Math.signum(instrument.dv01PerUnit());
      mid -= instrument.bpToPrice(v.skewBpPer100k * (ctx.netDv01() / 1e5) * sign);
    }
    final double distance = instrument.bpToPrice(h);
    final long tick = instrument.tickMantissa();
    final long bid = Prices.roundToTick(Prices.toMantissa(mid - distance), tick, false);
    final long ask = Prices.roundToTick(Prices.toMantissa(mid + distance), tick, true);
    ctx.quote(rfq.rfqId(), bid, ask);
  }

  /**
   * Half-spread maximising expected edge with the win probability averaged over the cover
   * distribution (minimum of dealers - 1 lognormals), instead of evaluated at a point estimate
   * of the cover. Same hit-rate model (central slope), same adverse and hedge-cost inputs.
   */
  private double coverIntegrated(
      final Instrument instrument, final double size, final int tier, final int dealers, final boolean inWindow) {
    final int k = Math.max(1, dealers - 1);
    final double median = competitors.medianBp(instrument, size);
    final double sigma = competitors.sigma();
    final int nz = 401;
    final double zLo = -5.0;
    final double dz = 10.0 / (nz - 1);
    final double[] cover = new double[nz];
    final double[] w = new double[nz];
    double wsum = 0;
    for (int i = 0; i < nz; i++) {
      final double z = zLo + i * dz;
      final double phi = Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
      w[i] = k * phi * Math.pow(1.0 - normCdf(z), k - 1) * dz;
      cover[i] = median * Math.exp(sigma * z);
      wsum += w[i];
    }
    final double cost = pricing.adverse(tier) + pricing.hedgeCost(instrument);
    double best = 0.0;
    double bestEdge = Double.NEGATIVE_INFINITY;
    final int steps = (int) Math.round(pricing.maxBp / pricing.stepBp);
    for (int s = 0; s <= steps; s++) {
      final double h = s * pricing.stepBp;
      double p = 0;
      for (int i = 0; i < nz; i++) {
        p += w[i] * centralHit.winProbability(h - cover[i], size, instrument.tenorYears(), inWindow, dealers);
      }
      final double edge = (p / wsum) * (h - cost);
      if (edge > bestEdge + 1e-12) {
        bestEdge = edge;
        best = h;
      }
    }
    return best;
  }

  /** Standard normal CDF (Abramowitz and Stegun 7.1.26 on erf, error below 1.5e-7). */
  static double normCdf(final double z) {
    final double x = Math.abs(z) / Math.sqrt(2);
    final double t = 1.0 / (1.0 + 0.3275911 * x);
    final double y =
        1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
            * t * Math.exp(-x * x);
    return z >= 0 ? 0.5 * (1 + y) : 0.5 * (1 - y);
  }

  @Override
  public void onQuoteResponse(final QuoteResponseDecoder response, final StrategyContext ctx) {
    if (response.status() == QuoteStatus.Done) {
      maybeHedge(ctx);
    }
  }

  @Override
  public void onExecutionReport(final ExecutionReportDecoder report, final StrategyContext ctx) {
    final OrdStatus s = report.ordStatus();
    if (s == OrdStatus.Filled || s == OrdStatus.Rejected || s == OrdStatus.Cancelled) {
      final Integer bucket = orderBucket.remove(report.clOrdId());
      inFlight[bucket == null ? 0 : bucket] = false;
      if (s == OrdStatus.Filled) {
        maybeHedge(ctx);
      }
    }
  }

  private void maybeHedge(final StrategyContext ctx) {
    if (v.hedgeMode.equals("bucketed_market")) {
      hedgeBucket(ctx, 0, FRONT, InstrumentSet.UST_2Y, bucketDv01(ctx, FRONT), OrdType.Market);
      hedgeBucket(ctx, 1, BACK, InstrumentSet.TY_FUTURE, bucketDv01(ctx, BACK), OrdType.Market);
    } else {
      final OrdType type = v.hedgeMode.equals("ty_passive") ? OrdType.Limit : OrdType.Market;
      hedgeBucket(ctx, 0, null, InstrumentSet.TY_FUTURE, ctx.netDv01(), type);
    }
  }

  private static double bucketDv01(final StrategyContext ctx, final int[] members) {
    double sum = 0;
    for (final int id : members) {
      sum += ctx.position(id) * ctx.instruments().get(id).dv01PerUnit();
    }
    return sum;
  }

  private void hedgeBucket(
      final StrategyContext ctx, final int bucket, final int[] members, final int hedgeId, final double net,
      final OrdType type) {
    if (inFlight[bucket] || !ctx.hasMid(hedgeId)) {
      return;
    }
    if (Math.abs(net) <= v.hedgeThresholdDv01) {
      return;
    }
    final Instrument h = ctx.instruments().get(hedgeId);
    final double perLot = h.dv01PerUnit() * h.lotSize();
    final long lots = Math.round(-net / perLot);
    if (lots == 0) {
      return;
    }
    final boolean buy = lots > 0;
    long price = 0L;
    if (type == OrdType.Limit) {
      final double half = ctx.bookHalfSpread(hedgeId);
      if (half <= 0) {
        return;
      }
      final double touch = buy ? ctx.mid(hedgeId) - half : ctx.mid(hedgeId) + half;
      price = Prices.roundToTick(Prices.toMantissa(touch), h.tickMantissa(), !buy);
    }
    inFlight[bucket] = true;
    final long id =
        ctx.sendOrder(hedgeId, buy ? Side.Buy : Side.Sell, type, Math.abs(lots) * h.lotSize(), price, true);
    orderBucket.put(id, bucket);
  }
}

/** What one run of one variant on one day contributes to the results. */
final class DayMetrics {
  double pnl;
  Attribution attribution;
  long rfqs;
  long quoted;
  long won;
  long hedgeFills;
  double clientDv01;
  double clientNotional;
  double hedgeDv01;
  double maxAbsNetDv01;
  // diagnostics: by tier (index 0..2) and by size bucket
  final double[] tierDv01 = new double[3];
  final double[] tierSpread = new double[3];
  final double[] tierMarkout1m = new double[3];
  final double[] tierMarkout5m = new double[3];
  final long[] tierWon = new long[3];
  final long[] tierQuoted = new long[3];
  final double[] sizeDv01 = new double[SIZE_EDGES.length + 1];
  final double[] sizeSpread = new double[SIZE_EDGES.length + 1];
  final double[] sizeMarkout5m = new double[SIZE_EDGES.length + 1];
  double moneyLeft;
  double coverGapWonDv01;
  double coverGapLostNearDv01;
  long lostNear;
  double favourSum;
  double favourPos;
  double favourSq;
  double eventSpread;
  double eventMarkout5m;
  double eventDv01;
  long eventWon;

  static final double[] SIZE_EDGES = {10, 50, 200};
  static final long EVENT_WINDOW_NANOS = 120_000_000_000L;

  static DayMetrics of(final BacktestResult r, final InstrumentSet instruments, final Variant v) {
    final DayMetrics m = new DayMetrics();
    m.pnl = Prices.cents(r.total().positionPnl());
    m.attribution = r.total();
    m.maxAbsNetDv01 = r.maxAbsNetDv01();
    final Map<Long, RfqRecord> byId = new HashMap<>();
    for (final RfqRecord q : r.rfqs()) {
      m.rfqs++;
      byId.put(q.rfqId(), q);
      if (!q.quoted()) {
        continue;
      }
      m.quoted++;
      m.tierQuoted[q.tier() - 1]++;
      final Instrument inst = instruments.get(q.instrumentId());
      final double tradeDv01 = Math.abs(q.quantity() * inst.dv01PerUnit());
      if (q.won()) {
        m.won++;
        m.tierWon[q.tier() - 1]++;
        // money left on the table: the cover was wider than our price by this much
        m.moneyLeft += Math.max(0.0, q.coverBp() - q.ourSpreadBp()) * tradeDv01;
        m.coverGapWonDv01 += (q.coverBp() - q.ourSpreadBp()) * tradeDv01;
      } else if (q.ourSpreadBp() - q.coverBp() < 0.05) {
        m.lostNear++;
        m.coverGapLostNearDv01 += (q.ourSpreadBp() - q.coverBp()) * tradeDv01;
      }
    }
    final long[] events = eventTimes(r, instruments);
    for (final FillRecord f : r.fills()) {
      final Instrument inst = instruments.get(f.instrumentId());
      final double dv01 = Math.abs(f.signedQuantity() * inst.dv01PerUnit());
      if (f.hedge()) {
        m.hedgeFills++;
        m.hedgeDv01 += dv01;
        continue;
      }
      m.clientDv01 += dv01;
      m.clientNotional += Math.abs(f.signedQuantity()) / 100.0;
      final int t = f.tier() - 1;
      final double edge = f.edge(inst);
      m.tierDv01[t] += dv01;
      m.tierSpread[t] += edge;
      m.tierMarkout1m[t] += f.markout(2);
      m.tierMarkout5m[t] += f.markout(3);
      final double sizeMm = Math.abs(f.signedQuantity()) / (double) InstrumentSet.MILLION;
      int b = 0;
      while (b < SIZE_EDGES.length && sizeMm >= SIZE_EDGES[b]) {
        b++;
      }
      m.sizeDv01[b] += dv01;
      m.sizeSpread[b] += edge;
      m.sizeMarkout5m[b] += f.markout(3);
      // the mid move between our quote and the client's decision, in the client's favour
      final RfqRecord q = byId.get(f.refId());
      if (q != null) {
        final double favour = -f.signedQuantity() * (f.mid() - q.midAtQuote()) * inst.valuePerUnit();
        m.favourSum += favour;
        m.favourPos += Math.max(0.0, favour);
        m.favourSq += favour * favour;
      }
      for (final long e : events) {
        if (Math.abs(f.timeNanos() - e) <= EVENT_WINDOW_NANOS) {
          m.eventWon++;
          m.eventDv01 += dv01;
          m.eventSpread += edge;
          m.eventMarkout5m += f.markout(3);
          break;
        }
      }
    }
    return m;
  }

  /** The two scheduled releases that carry a price jump in the synthetic market (08:30, 10:00 ET). */
  private static long[] eventTimes(final BacktestResult r, final InstrumentSet instruments) {
    final LocalDate date =
        LocalDate.ofInstant(Instant.ofEpochSecond(r.startNanos() / 1_000_000_000L), java.time.ZoneOffset.UTC);
    final long midnight = date.atStartOfDay().toEpochSecond(java.time.ZoneOffset.ofHours(-5)) * 1_000_000_000L;
    return new long[] {midnight + 510L * 60_000_000_000L, midnight + 600L * 60_000_000_000L};
  }

  static double meanClientDv01(final DayMetrics[] days) {
    double s = 0;
    for (final DayMetrics d : days) {
      s += d.clientDv01;
    }
    return s / days.length;
  }

  static double meanNotional(final DayMetrics[] days) {
    double s = 0;
    for (final DayMetrics d : days) {
      s += d.clientNotional;
    }
    return s / days.length;
  }

  /** Sums over days, as per-day means where it reads better. */
  static Map<String, Object> summary(final DayMetrics[] days) {
    final int n = days.length;
    final DayMetrics t = new DayMetrics();
    Attribution a = Attribution.ZERO;
    double maxNet = 0;
    for (final DayMetrics d : days) {
      t.rfqs += d.rfqs;
      t.quoted += d.quoted;
      t.won += d.won;
      t.hedgeFills += d.hedgeFills;
      t.clientDv01 += d.clientDv01;
      t.clientNotional += d.clientNotional;
      t.hedgeDv01 += d.hedgeDv01;
      maxNet = Math.max(maxNet, d.maxAbsNetDv01);
      a = a.plus(d.attribution);
      for (int i = 0; i < 3; i++) {
        t.tierDv01[i] += d.tierDv01[i];
        t.tierSpread[i] += d.tierSpread[i];
        t.tierMarkout1m[i] += d.tierMarkout1m[i];
        t.tierMarkout5m[i] += d.tierMarkout5m[i];
        t.tierWon[i] += d.tierWon[i];
        t.tierQuoted[i] += d.tierQuoted[i];
      }
      for (int i = 0; i < t.sizeDv01.length; i++) {
        t.sizeDv01[i] += d.sizeDv01[i];
        t.sizeSpread[i] += d.sizeSpread[i];
        t.sizeMarkout5m[i] += d.sizeMarkout5m[i];
      }
      t.moneyLeft += d.moneyLeft;
      t.coverGapWonDv01 += d.coverGapWonDv01;
      t.coverGapLostNearDv01 += d.coverGapLostNearDv01;
      t.lostNear += d.lostNear;
      t.favourSum += d.favourSum;
      t.favourPos += d.favourPos;
      t.favourSq += d.favourSq;
      t.eventSpread += d.eventSpread;
      t.eventMarkout5m += d.eventMarkout5m;
      t.eventDv01 += d.eventDv01;
      t.eventWon += d.eventWon;
    }
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("rfqs", t.rfqs);
    m.put("quoted", t.quoted);
    m.put("won", t.won);
    m.put("hit_rate", t.quoted == 0 ? 0.0 : Audit.round(t.won / (double) t.quoted, 6));
    m.put("hedge_fills", t.hedgeFills);
    m.put("client_dv01_traded_per_day", Audit.round(t.clientDv01 / n, 2));
    m.put("client_notional_traded_per_day", Audit.round(t.clientNotional / n, 2));
    m.put("hedge_dv01_traded_per_day", Audit.round(t.hedgeDv01 / n, 2));
    m.put("max_abs_net_dv01", Audit.round(maxNet, 2));
    final Map<String, Object> attr = new LinkedHashMap<>();
    for (final Map.Entry<String, Object> e : a.asMap().entrySet()) {
      attr.put(e.getKey(), Prices.cents((Double) e.getValue()));
    }
    m.put("attribution_total", attr);
    final Map<String, Object> diag = new LinkedHashMap<>();
    final List<Object> tiers = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      final Map<String, Object> x = new LinkedHashMap<>();
      x.put("tier", (long) (i + 1));
      x.put("quoted", t.tierQuoted[i]);
      x.put("won", t.tierWon[i]);
      x.put("dv01_per_day", Audit.round(t.tierDv01[i] / n, 2));
      x.put("spread_capture_per_day", Prices.cents(t.tierSpread[i] / n));
      x.put("markout_1min_per_day", Prices.cents(t.tierMarkout1m[i] / n));
      x.put("markout_5min_per_day", Prices.cents(t.tierMarkout5m[i] / n));
      x.put("mid_move_5min_dealer_per_day", Prices.cents((t.tierMarkout5m[i] - t.tierSpread[i]) / n));
      x.put("spread_bp_of_dv01", t.tierDv01[i] == 0 ? 0.0 : Audit.round(t.tierSpread[i] / t.tierDv01[i], 4));
      x.put("mid_move_5min_dealer_bp_of_dv01",
          t.tierDv01[i] == 0 ? 0.0 : Audit.round((t.tierMarkout5m[i] - t.tierSpread[i]) / t.tierDv01[i], 4));
      tiers.add(x);
    }
    diag.put("by_tier", tiers);
    final List<Object> sizes = new ArrayList<>();
    final String[] labels = {"<10mm", "10-50mm", "50-200mm", ">=200mm"};
    for (int i = 0; i < t.sizeDv01.length; i++) {
      final Map<String, Object> x = new LinkedHashMap<>();
      x.put("size", labels[i]);
      x.put("dv01_per_day", Audit.round(t.sizeDv01[i] / n, 2));
      x.put("spread_capture_per_day", Prices.cents(t.sizeSpread[i] / n));
      x.put("mid_move_5min_dealer_per_day", Prices.cents((t.sizeMarkout5m[i] - t.sizeSpread[i]) / n));
      x.put("mid_move_5min_dealer_bp_of_dv01",
          t.sizeDv01[i] == 0 ? 0.0 : Audit.round((t.sizeMarkout5m[i] - t.sizeSpread[i]) / t.sizeDv01[i], 4));
      sizes.add(x);
    }
    diag.put("by_size", sizes);
    diag.put("money_left_on_table_per_day", Prices.cents(t.moneyLeft / n));
    diag.put("mean_cover_gap_won_bp_dv01_weighted",
        t.tierDv01[0] + t.tierDv01[1] + t.tierDv01[2] == 0 ? 0.0
            : Audit.round(t.coverGapWonDv01 / (t.tierDv01[0] + t.tierDv01[1] + t.tierDv01[2]), 4));
    diag.put("lost_within_0p05bp_of_cover", t.lostNear);
    diag.put("decision_window_move_client_favour_per_day", Prices.cents(t.favourSum / n));
    diag.put("decision_window_move_positive_part_per_day", Prices.cents(t.favourPos / n));
    diag.put("decision_window_move_rms_usd", t.won == 0 ? 0.0 : Prices.cents(Math.sqrt(t.favourSq / t.won)));
    diag.put("event_window_won", t.eventWon);
    diag.put("event_window_dv01_per_day", Audit.round(t.eventDv01 / n, 2));
    diag.put("event_window_spread_per_day", Prices.cents(t.eventSpread / n));
    diag.put("event_window_markout_5min_per_day", Prices.cents(t.eventMarkout5m / n));
    m.put("diagnostics", diag);
    return m;
  }
}
