// The P&L audit re-run on the production engine (audit fix package D, pre-registrations
// AUDIT-11 to AUDIT-14). Java source-file script, run by research/audit/run_desk_audit.sh.
//
// Each session is a day of the D2C RFQ and CLOB simulators recorded by rates-app's TradingProcess
// (driven single-threaded with step, so the journal is fixed by its seed) with the production desk
// in the loop. Its inbound journal is exported (JournalExport) and replayed through the P5
// back-tester with the production desk as the strategy (DeskStrategy), exactly as
// DeskBacktest.run does, at each point of the usd_rfq_hit_rate band and for each variant named in
// the audit config. Variants change only what the production code already exposes: the desk's
// QuotingConfig (mutable, read live by QuotingEngine), the DeskSettings record, and the
// back-tester's client rule, hedge fill rule and carry model. One ledger row per pre-registration;
// a second evaluation look is refused.

import io.ratesalgo.app.TradingProcess;
import io.ratesalgo.app.backtest.DeskBacktest;
import io.ratesalgo.app.backtest.DeskStrategy;
import io.ratesalgo.app.backtest.JournalExport;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.UsdUniverse;
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
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.model.RfqOutcomeModel;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.pnl.CarryModel;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.prereg.PreRegistration;
import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.quoting.QuotingConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

public final class DeskAudit {

  private DeskAudit() {}

  public static void main(final String[] args) throws IOException {
    final Map<String, String> opts = new LinkedHashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("expected --name value pairs, got " + args[i]);
      }
      opts.put(args[i].substring(2), args[i + 1]);
    }
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final PreRegistration prereg = PreRegistration.load(repo.resolve(require(opts, "prereg"))).requireValid(repo);
    final Path ledgerFile = repo.resolve(opts.getOrDefault("ledger", "data/ledger/ledger.jsonl"));
    final Path work =
        Path.of(opts.getOrDefault("work", System.getProperty("java.io.tmpdir") + "/desk-audit")).toAbsolutePath();
    final boolean pilot = opts.containsKey("days");
    if (pilot && ledgerFile.normalize().equals(repo.resolve("data/ledger/ledger.jsonl").normalize())) {
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
    final DeskRun run = new DeskRun(repo, config, prereg.baseline(), prereg.treatments());
    final DeskRun.Outcome outcome =
        run.run(days, work, Integer.parseInt(opts.getOrDefault("threads", "10")));

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
    record.put("harness", "research/audit/DeskAudit.java");
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

  static String git(final Path repo, final String... args) {
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

/** Records the sessions, replays every variant at every band point and computes the statistics. */
final class DeskRun {

  record Outcome(String verdict, Map<String, Object> results, List<Map<String, Object>> journals) {}

  private static final long MINUTE = 60_000_000_000L;

  private final Map<String, Object> config;
  private final Presets presets;
  private final String baseline;
  private final List<String> treatments;
  private final Map<String, DeskVariant> variants = new LinkedHashMap<>();
  private final UsdUniverse universe = UsdUniverse.standard();
  private final InstrumentSet instruments = DeskBacktest.instruments(universe);
  private final CarryModel coupons;

  DeskRun(final Path repo, final Map<String, Object> config, final String baseline, final List<String> treatments) {
    this.config = config;
    this.presets = Presets.load(repo.resolve(Yaml.string(config, "presets", "audit config")));
    this.baseline = baseline;
    this.treatments = treatments;
    final Map<String, Object> all = Yaml.map(config, "variants", "audit config");
    final List<String> names = new ArrayList<>();
    names.add(baseline);
    names.addAll(treatments);
    for (final String n : names) {
      variants.put(n, new DeskVariant(n, Yaml.map(all, n, "variants")));
    }
    final Map<Integer, Double> c = new HashMap<>();
    for (final UsdUniverse.BondSpec b : universe.bonds()) {
      // couponMantissa is the annual coupon in percent with exponent -9
      c.put(b.instrumentId(), b.bond().couponMantissa() / 1e9 / 100.0);
    }
    this.coupons = CarryModel.bondCoupons(c);
  }

  Outcome run(final List<LocalDate> days, final Path work, final int threads) throws IOException {
    final Map<String, Object> st = Yaml.map(config, "statistics", "audit config");
    final int block = (int) Yaml.integer(st, "bootstrap_block_days", "statistics");
    final int resamples = (int) Yaml.integer(st, "bootstrap_resamples", "statistics");
    final long bootSeed = Yaml.integer(st, "bootstrap_seed", "statistics");
    final double level = Yaml.number(st, "level", "statistics");
    final double alpha = Yaml.number(st, "alpha", "statistics");
    final Map<String, Object> session = Yaml.map(config, "session", "audit config");
    final long seedBase = Yaml.integer(session, "day_seed_base", "session");
    final long minutes = Yaml.integer(session, "minutes", "session");
    final long stepNanos = Yaml.integer(session, "step_ms", "session") * 1_000_000L;
    final long outcomeSeed = Yaml.integer(config, "outcome_seed", "audit config");

    final List<String> names = new ArrayList<>(variants.keySet());
    final BandPoint[] points = BandPoint.values();
    final DeskMetrics[][][] metrics = new DeskMetrics[points.length][names.size()][days.size()];
    final List<Map<String, Object>> journals = new ArrayList<>();
    Files.createDirectories(work);
    // replays of one session are independent (each has its own desk, loop and back-tester), so
    // they run in parallel; every result lands in its own slot, so the outcome does not depend on
    // the schedule
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    for (int d = 0; d < days.size(); d++) {
      final LocalDate day = days.get(d);
      final long seed = seedBase + day.toEpochDay();
      final Path journal = work.resolve("session-" + seed + "-" + minutes + "m.raj");
      final Path stamp = work.resolve("session-" + seed + "-" + minutes + "m.frames");
      if (!Files.isRegularFile(stamp)) {
        // a recorded session depends only on its seed, length and the code, so the four
        // pre-registrations share it; the sha256 in each row pins what was replayed
        final Path dayDir = work.resolve("record-" + seed);
        deleteTree(dayDir);
        Files.deleteIfExists(journal);
        try (TradingProcess p =
            TradingProcess.start(new TradingProcess.Options(dayDir.resolve("journal"), seed, DeskSettings.defaults()))) {
          for (long m = 0; m < minutes; m++) {
            p.step(MINUTE, stepNanos);
          }
          final Path groupDir = p.group(0).dir();
          p.close();
          final long n = JournalExport.export(groupDir, "in", journal, 256 << 20);
          Files.writeString(stamp, Long.toString(n), StandardCharsets.UTF_8);
        }
        deleteTree(dayDir);
      }
      final long frames = Long.parseLong(Files.readString(stamp, StandardCharsets.UTF_8).trim());
      final Map<String, Object> j = new LinkedHashMap<>();
      j.put("date", day.toString());
      j.put("seed", seed);
      j.put("sha256", Hashing.sha256File(journal));
      j.put("frames", frames);
      final List<Callable<Void>> jobs = new ArrayList<>();
      final int dd = d;
      for (int p = 0; p < points.length; p++) {
        final BacktestConfig standard =
            BacktestConfig.fromPresets(presets, List.of(journal), outcomeSeed, points[p], null);
        for (int s = 0; s < names.size(); s++) {
          final DeskVariant v = variants.get(names.get(s));
          final int pp = p;
          final int ss = s;
          jobs.add(() -> {
            final BacktestConfig cfg = v.config(standard, instruments, coupons);
            final VariantDesk strategy = new VariantDesk(new DeskStrategy(universe, v.settings()), v, seed);
            final BacktestResult r = new BacktestRunner(cfg).run(strategy);
            metrics[pp][ss][dd] = DeskMetrics.of(r, instruments, strategy);
            return null;
          });
        }
      }
      try {
        for (final Future<Void> f : pool.invokeAll(jobs)) {
          f.get();
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      } catch (final ExecutionException e) {
        throw new IllegalStateException("replay failed on " + day, e.getCause());
      }
      j.put("rfqs", metrics[0][0][d].rfqs);
      journals.add(j);
      System.out.println("  " + day + " done (" + j.get("rfqs") + " RFQs)");
    }
    pool.shutdown();

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
    results.put("engine", "production desk (rates-app DeskStrategy) replayed by the P5 back-tester");
    results.put("metric_unit", "USD per recorded session, total position P&L rounded to cents");
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
        m.putAll(DeskMetrics.summary(metrics[p][s]));
        strategies.put(names.get(s), m);
      }
      band.put("strategies", strategies);
      final double baseDv01 = DeskMetrics.meanClientDv01(metrics[p][0]);
      final double baseNotional = DeskMetrics.meanNotional(metrics[p][0]);
      final Map<String, Object> comparisons = new LinkedHashMap<>();
      for (int t = 0; t < treatments.size(); t++) {
        final int k = p * treatments.size() + t;
        final Map<String, Object> c = new LinkedHashMap<>();
        c.put("mean_difference", Prices.cents(welch[k].difference()));
        c.put("paired_ci_low", Prices.cents(diffCi[k].lower()));
        c.put("paired_ci_high", Prices.cents(diffCi[k].upper()));
        final double scale = baseDv01 > 0 ? baseDv01 / 1e6 : Double.NaN;
        c.put("per_mm_dv01_traded", round(welch[k].difference() / scale, 2));
        c.put("per_mm_dv01_ci_low", round(diffCi[k].lower() / scale, 2));
        c.put("per_mm_dv01_ci_high", round(diffCi[k].upper() / scale, 2));
        c.put("bp_of_notional", baseNotional > 0 ? round(1e4 * welch[k].difference() / baseNotional, 6) : 0.0);
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
    // no replica in this harness: the engine is the production desk itself (kept for summarize.py)
    results.put("replica_check", Map.of("checks", 0L, "mismatches", 0L));
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

  private static double[] pnl(final DeskMetrics[] days) {
    final double[] x = new double[days.length];
    for (int i = 0; i < x.length; i++) {
      x[i] = days[i].pnl;
    }
    return x;
  }

  static double round(final double v, final int digits) {
    if (!Double.isFinite(v)) {
      return v;
    }
    return new java.math.BigDecimal(v).setScale(digits, java.math.RoundingMode.HALF_EVEN).doubleValue();
  }

  private static void deleteTree(final Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> s = Files.walk(dir)) {
      for (final Path p : s.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}

/** One variant: what it changes on the desk and in the back-tester. */
final class DeskVariant {

  final String name;
  final String client;
  final String hedgeFill;
  final boolean couponCarry;
  final Double minHalfSpreadBp;
  final Double maxHalfSpreadBp;
  final double extraHalfSpreadBp;
  final Double kappa;
  final Double maxSkewBp;
  final double eventMultiplier;
  final long guardBeforeNanos;
  final long guardAfterNanos;
  final List<Long> guardMinutesEt = new ArrayList<>();
  final Long hedgeIntervalMs;
  final Double hedgeMaxLots;

  DeskVariant(final String name, final Map<String, Object> m) {
    this.name = name;
    this.client = m.containsKey("client") ? Yaml.string(m, "client", name) : "hit_rate";
    this.hedgeFill = m.containsKey("hedge_fill") ? Yaml.string(m, "hedge_fill", name) : "depth";
    this.couponCarry = !m.containsKey("coupon_carry") || Boolean.TRUE.equals(m.get("coupon_carry"));
    this.minHalfSpreadBp = m.containsKey("min_half_spread_bp") ? Yaml.number(m, "min_half_spread_bp", name) : null;
    this.maxHalfSpreadBp = m.containsKey("max_half_spread_bp") ? Yaml.number(m, "max_half_spread_bp", name) : null;
    this.extraHalfSpreadBp = m.containsKey("extra_half_spread_bp") ? Yaml.number(m, "extra_half_spread_bp", name) : 0.0;
    this.kappa = m.containsKey("kappa") ? Yaml.number(m, "kappa", name) : null;
    this.maxSkewBp = m.containsKey("max_skew_bp") ? Yaml.number(m, "max_skew_bp", name) : null;
    this.eventMultiplier = m.containsKey("event_multiplier") ? Yaml.number(m, "event_multiplier", name) : 0.0;
    this.guardBeforeNanos = m.containsKey("guard_before_s") ? Yaml.integer(m, "guard_before_s", name) * 1_000_000_000L : 0L;
    this.guardAfterNanos = m.containsKey("guard_after_s") ? Yaml.integer(m, "guard_after_s", name) * 1_000_000_000L : 0L;
    if (m.containsKey("guard_minutes_et")) {
      for (final Object o : Yaml.asList(m.get("guard_minutes_et"), "guard_minutes_et")) {
        guardMinutesEt.add(((Number) o).longValue());
      }
    }
    this.hedgeIntervalMs = m.containsKey("hedge_interval_ms") ? Yaml.integer(m, "hedge_interval_ms", name) : null;
    this.hedgeMaxLots = m.containsKey("hedge_max_lots") ? Yaml.number(m, "hedge_max_lots", name) : null;
    if (!client.equals("hit_rate") && !client.equals("best_price")) {
      throw new IllegalArgumentException(name + ": client must be hit_rate or best_price");
    }
    if (!hedgeFill.equals("depth") && !hedgeFill.equals("linear")) {
      throw new IllegalArgumentException(name + ": hedge_fill must be depth or linear");
    }
  }

  DeskSettings settings() {
    final DeskSettings d = DeskSettings.defaults();
    return new DeskSettings(
        d.housekeepingNanos(),
        hedgeIntervalMs == null ? d.hedgeIntervalNanos() : hedgeIntervalMs * 1_000_000L,
        d.tradeRatePerSecond(),
        d.hedgeLotMinor(),
        hedgeMaxLots == null ? d.hedgeMaxLots() : hedgeMaxLots,
        d.seed(),
        d.quoteRatePerSecond());
  }

  BacktestConfig config(final BacktestConfig s, final InstrumentSet instruments, final CarryModel coupons) {
    final RfqOutcomeModel outcomes =
        s.outcomes().withClientChoice(
            client.equals("best_price") ? RfqOutcomeModel.ClientChoice.BEST_PRICE : RfqOutcomeModel.ClientChoice.HIT_RATE);
    return new BacktestConfig(
        s.journals(), instruments, s.seed(), s.bandPoint(), outcomes, s.strategyHitRate(),
        s.hedgeCost().withDepthWalk(hedgeFill.equals("depth")), s.clob(), couponCarry ? coupons : CarryModel.NONE,
        s.calendar(), s.clientsPerTier(), s.quoteLatencyNanos(), s.clientDecisionNanos(), s.orderLatencyNanos(),
        s.markoutHorizonsNanos(), null);
  }

  /** Applies the quoting changes to the desk's live configuration. */
  /** The half-spread clamp of DeskSettings.quoting(); QuotingConfig has no getter for it. */
  static final double DESK_MIN_HALF_SPREAD_BP = 0.02;

  static final double DESK_MAX_HALF_SPREAD_BP = 0.08;

  double minHalfSpread() {
    return (minHalfSpreadBp == null ? DESK_MIN_HALF_SPREAD_BP : minHalfSpreadBp) + extraHalfSpreadBp;
  }

  double maxHalfSpread() {
    return (maxHalfSpreadBp == null ? DESK_MAX_HALF_SPREAD_BP : maxHalfSpreadBp) + extraHalfSpreadBp;
  }

  void apply(final QuotingConfig q, final long sessionStartNanos) {
    if (minHalfSpreadBp != null || maxHalfSpreadBp != null || extraHalfSpreadBp != 0.0) {
      q.minHalfSpreadBp(minHalfSpread());
      q.maxHalfSpreadBp(maxHalfSpread());
    }
    if (kappa != null) {
      q.kappa(kappa);
    }
    if (maxSkewBp != null) {
      q.maxSkewBp(maxSkewBp);
    }
    if (eventMultiplier > 0) {
      final LocalDate date = LocalDate.ofInstant(Instant.ofEpochSecond(sessionStartNanos / 1_000_000_000L), ZoneOffset.UTC);
      // New York is UTC-5 on the simulators' trading date (2026-11-02)
      final long midnight = date.atStartOfDay().toEpochSecond(ZoneOffset.ofHours(-5)) * 1_000_000_000L;
      for (final long minute : guardMinutesEt) {
        final long t = midnight + minute * 60_000_000_000L;
        q.addEvent(t - guardBeforeNanos, t + guardAfterNanos, eventMultiplier);
      }
    }
  }
}

/** The production desk with a variant's quoting changes applied when it starts. */
final class VariantDesk implements Strategy {

  private final DeskStrategy desk;
  private final DeskVariant variant;
  final long seed;

  VariantDesk(final DeskStrategy desk, final DeskVariant variant, final long seed) {
    this.desk = desk;
    this.variant = variant;
    this.seed = seed;
  }

  @Override
  public String name() {
    return "desk:" + variant.name;
  }

  @Override
  public void onStart(final StrategyContext ctx) {
    desk.onStart(ctx);
    final QuotingConfig q = desk.desk().desk().quoting().config();
    variant.apply(q, ctx.nowEpochNanos());
  }

  @Override
  public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {
    desk.onQuoteRequest(rfq, ctx);
  }

  @Override
  public void onBookUpdate(final BookUpdateDecoder update, final StrategyContext ctx) {
    desk.onBookUpdate(update, ctx);
  }

  @Override
  public void onCurveInput(final CurveInputDecoder input, final StrategyContext ctx) {
    desk.onCurveInput(input, ctx);
  }

  @Override
  public void onQuoteResponse(final QuoteResponseDecoder response, final StrategyContext ctx) {
    desk.onQuoteResponse(response, ctx);
  }

  @Override
  public void onExecutionReport(final ExecutionReportDecoder report, final StrategyContext ctx) {
    desk.onExecutionReport(report, ctx);
  }

  @Override
  public void onTimer(final long timerId, final long nowEpochNanos, final StrategyContext ctx) {
    desk.onTimer(timerId, nowEpochNanos, ctx);
  }

  long quotes() {
    return desk.quotes();
  }

  long orders() {
    return desk.orders();
  }
}

/** What one replay of one session produced. */
final class DeskMetrics {

  double pnl;
  Attribution attribution;
  long rfqs;
  long quoted;
  long won;
  long hedgeFills;
  long hedgeOrders;
  double clientDv01;
  double clientNotional;
  double hedgeDv01;
  double coverGapWonDv01;
  double moneyLeft;
  double markout5m;
  double maxAbsNetDv01;

  static DeskMetrics of(final BacktestResult r, final InstrumentSet instruments, final VariantDesk desk) {
    final DeskMetrics m = new DeskMetrics();
    m.pnl = Prices.cents(r.total().positionPnl());
    m.attribution = r.total();
    m.maxAbsNetDv01 = r.maxAbsNetDv01();
    m.hedgeOrders = desk.orders();
    for (final RfqRecord q : r.rfqs()) {
      m.rfqs++;
      if (!q.quoted()) {
        continue;
      }
      m.quoted++;
      if (q.won()) {
        m.won++;
        final double tradeDv01 = Math.abs(q.quantity() * instruments.get(q.instrumentId()).dv01PerUnit());
        m.coverGapWonDv01 += (q.coverBp() - q.ourSpreadBp()) * tradeDv01;
        m.moneyLeft += Math.max(0.0, q.coverBp() - q.ourSpreadBp()) * tradeDv01;
      }
    }
    for (final FillRecord f : r.fills()) {
      final Instrument inst = instruments.get(f.instrumentId());
      final double dv01 = Math.abs(f.signedQuantity() * inst.dv01PerUnit());
      if (f.hedge()) {
        m.hedgeFills++;
        m.hedgeDv01 += dv01;
      } else {
        m.clientDv01 += dv01;
        m.clientNotional += Math.abs(f.signedQuantity()) / 100.0;
        m.markout5m += f.markout(3);
      }
    }
    return m;
  }

  static double meanClientDv01(final DeskMetrics[] days) {
    double s = 0;
    for (final DeskMetrics d : days) {
      s += d.clientDv01;
    }
    return s / days.length;
  }

  static double meanNotional(final DeskMetrics[] days) {
    double s = 0;
    for (final DeskMetrics d : days) {
      s += d.clientNotional;
    }
    return s / days.length;
  }

  static Map<String, Object> summary(final DeskMetrics[] days) {
    final int n = days.length;
    double spread = 0;
    double inventory = 0;
    double hedge = 0;
    double carry = 0;
    double funding = 0;
    double fees = 0;
    double maxResidual = 0;
    long rfqs = 0;
    long quoted = 0;
    long won = 0;
    long hedgeFills = 0;
    long hedgeOrders = 0;
    double clientDv01 = 0;
    double hedgeDv01 = 0;
    double gap = 0;
    double left = 0;
    double markout = 0;
    double maxNet = 0;
    for (final DeskMetrics d : days) {
      final Attribution a = d.attribution;
      spread += a.spreadCapture();
      inventory += a.inventoryMtm();
      hedge += a.hedgeCost();
      carry += a.carryRoll();
      funding += a.funding();
      fees += a.fees();
      maxResidual = Math.max(maxResidual, Math.abs(a.residual()));
      rfqs += d.rfqs;
      quoted += d.quoted;
      won += d.won;
      hedgeFills += d.hedgeFills;
      hedgeOrders += d.hedgeOrders;
      clientDv01 += d.clientDv01;
      hedgeDv01 += d.hedgeDv01;
      gap += d.coverGapWonDv01;
      left += d.moneyLeft;
      markout += d.markout5m;
      maxNet = Math.max(maxNet, d.maxAbsNetDv01);
    }
    final Map<String, Object> m = new LinkedHashMap<>();
    final Map<String, Object> attr = new LinkedHashMap<>();
    attr.put("spread_capture", Prices.cents(spread / n));
    attr.put("inventory_mtm", Prices.cents(inventory / n));
    attr.put("hedge_cost", Prices.cents(hedge / n));
    attr.put("carry_roll", Prices.cents(carry / n));
    attr.put("funding", Prices.cents(funding / n));
    attr.put("fees", Prices.cents(fees / n));
    m.put("attribution_mean_per_day", attr);
    final Map<String, Object> total = new LinkedHashMap<>();
    total.put("spread_capture", Prices.cents(spread));
    total.put("inventory_mtm", Prices.cents(inventory));
    total.put("hedge_cost", Prices.cents(hedge));
    total.put("carry_roll", Prices.cents(carry));
    total.put("funding", Prices.cents(funding));
    total.put("fees", Prices.cents(fees));
    m.put("attribution_total", total);
    m.put("max_abs_residual", maxResidual);
    m.put("rfqs_per_day", DeskRun.round((double) rfqs / n, 2));
    m.put("quoted_per_day", DeskRun.round((double) quoted / n, 2));
    m.put("won_per_day", DeskRun.round((double) won / n, 2));
    m.put("hit_rate", quoted == 0 ? 0.0 : DeskRun.round((double) won / quoted, 6));
    m.put("hedge_orders_per_day", DeskRun.round((double) hedgeOrders / n, 2));
    m.put("hedge_fills_per_day", DeskRun.round((double) hedgeFills / n, 2));
    m.put("client_dv01_per_day", Prices.cents(clientDv01 / n));
    m.put("client_dv01_traded_per_day", Prices.cents(clientDv01 / n));
    m.put("hedge_dv01_per_day", Prices.cents(hedgeDv01 / n));
    m.put("hedge_cost_bp_per_dv01_hedged", hedgeDv01 == 0 ? 0.0 : DeskRun.round(-hedge / hedgeDv01, 6));
    m.put("won_cover_gap_bp_dv01_weighted", clientDv01 == 0 ? 0.0 : DeskRun.round(gap / clientDv01, 6));
    m.put("money_left_per_day", Prices.cents(left / n));
    m.put("client_markout_5m_per_day", Prices.cents(markout / n));
    m.put("max_abs_net_dv01", Prices.cents(maxNet));
    return m;
  }
}
