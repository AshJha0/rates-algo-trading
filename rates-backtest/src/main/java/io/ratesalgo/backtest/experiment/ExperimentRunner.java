package io.ratesalgo.backtest.experiment;

import io.ratesalgo.backtest.prereg.DataSets;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.prereg.PreRegistration;
import io.ratesalgo.backtest.prereg.PreRegistrationException;
import io.ratesalgo.backtest.sim.PairedSessions;
import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.sim.SimParams;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a registered simulator v2 experiment's sessions in parallel worker processes, and its
 * pilot (docs/research/SIM_V2_DESIGN.md section 12).
 *
 * <pre>
 *   ExperimentRunner run    --prereg P --out DIR --factory CLASS [--workers N] [--worker-heap 1536m]
 *                           [--repo .] [--ledger data/ledger/ledger.jsonl] [--allow-dirty] [--retry-failed]
 *   ExperimentRunner pilot  --prereg P --pilot N --out DIR --factory CLASS [--arms a,b] [--workers N]
 *   ExperimentRunner status --out DIR
 *   ExperimentRunner validate --prereg P [--repo .]
 * </pre>
 *
 * <p>run: refuses (exit 2, nothing run) a draft, an invalid file, a session count that is not
 * fixed, an arm the factory cannot build, a band row the simulator refuses, an output directory
 * inside a git work tree, a data set this pre-registration has already read, and earlier looks by
 * other pre-registrations it does not declare under {@code prior_looks}. Otherwise it writes the
 * manifest (or checks that a resumed run is the same run), runs the missing sessions in at most
 * {@code workers} processes at a time (default: the machine's cores less four), and only when
 * every registered session is present computes the summary once, writes {@code summary.json} and
 * appends one ledger row. An incomplete run exits 3 and records nothing.
 *
 * <p>pilot: runs sessions 0..N-1 of the configuration's pilot base (never the registered base),
 * central parameters only, the baseline arm (or the arms given), and reports the session standard
 * deviation of P&amp;L, the implied number of sessions by the protocol formula, and wall-clock per
 * session with a projection for the registered design. It writes no ledger row; the pilot is
 * discarded for inference. The mean P&amp;L is neither printed nor written.
 *
 * <p>status: reads the manifest, the timing files, the heartbeat and the failure marker; it never
 * opens a session file.
 */
public final class ExperimentRunner {

  public static final int OK = 0;
  public static final int ERROR = 1;
  public static final int REFUSED = 2;
  public static final int INCOMPLETE = 3;

  /** Cores left free by default for the rest of the machine. */
  public static final int CORES_LEFT_FREE = 4;

  private static final long HEARTBEAT_SECONDS = 60;

  private ExperimentRunner() {}

  public static void main(final String[] args) {
    final int code = run(args, new PrintStream(System.out, true, StandardCharsets.UTF_8));
    if (code != OK) {
      System.exit(code);
    }
  }

  /** Default worker count: every core but four, at least one. */
  public static int defaultWorkers() {
    return Math.max(1, Runtime.getRuntime().availableProcessors() - CORES_LEFT_FREE);
  }

  /** Runs a command; returns the exit code. */
  public static int run(final String[] args, final PrintStream out) {
    if (args.length == 0) {
      out.println("usage: ExperimentRunner run|pilot|status|validate --name value ...");
      return ERROR;
    }
    final Map<String, String> opts = parse(args, 1);
    try {
      return switch (args[0]) {
        case "run" -> runRegistered(opts, out);
        case "pilot" -> pilot(opts, out);
        case "status" -> status(new Checkpoints(Path.of(require(opts, "out"))), out);
        case "validate" -> validate(opts, out);
        default -> {
          out.println("unknown command " + args[0]);
          yield ERROR;
        }
      };
    } catch (final PreRegistrationException e) {
      out.println("refused:");
      for (final String p : e.problems()) {
        out.println("  " + p);
      }
      return REFUSED;
    }
  }

  // ------------------------------------------------------------------ commands

  private static int validate(final Map<String, String> opts, final PrintStream out) {
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final PreRegistration prereg = PreRegistration.load(repo.resolve(require(opts, "prereg")));
    final List<String> problems = prereg.validate(repo);
    if (problems.isEmpty()) {
      out.println(prereg.id() + ": valid" + (prereg.isDraft() ? " (draft: validates, is not run)" : ""));
      return OK;
    }
    out.println(prereg.id() + ": " + problems.size() + " problem(s)");
    for (final String p : problems) {
      out.println("  " + p);
    }
    return REFUSED;
  }

  private static int runRegistered(final Map<String, String> opts, final PrintStream out) {
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final PreRegistration prereg = PreRegistration.load(repo.resolve(require(opts, "prereg")));
    final SessionDesign design = SessionDesign.of(prereg, repo);
    final List<String> refusals = new ArrayList<>();
    if (design.draft()) {
      refusals.add(prereg.id() + " is a draft: sign it (remove the draft status and commit it) before the run");
    }
    if (design.sessions() < 0) {
      refusals.add(prereg.id() + ": data_set.sessions is not fixed; write the count from the pilot before the run");
    }
    final ArmFactory factory = ArmFactory.load(require(opts, "factory"));
    for (final String arm : design.arms()) {
      final String r = factory.refusal(arm);
      if (r != null) {
        refusals.add("arm " + arm + ": " + r);
      }
    }
    final List<BandParameters.Row> rows;
    try {
      rows = BandParameters.rows(SimParams.loadDefault(), design.bandParameters());
    } catch (final IllegalArgumentException e) {
      refusals.add(e.getMessage());
      return refuse(out, refusals);
    }
    final Path outDir = Path.of(require(opts, "out")).toAbsolutePath().normalize();
    final String insideGit = gitWorkTreeOf(outDir);
    if (insideGit != null) {
      refusals.add("the output directory " + outDir + " is inside the git work tree " + insideGit + "; use a directory outside git");
    }
    final GitState git = GitState.of(repo, opts);
    if (git.dirty && !opts.containsKey("allow-dirty")) {
      refusals.add("the checkout has uncommitted changes; commit them or pass --allow-dirty (recorded as code_dirty)");
    }
    final Ledger ledger = new Ledger(repo.resolve(opts.getOrDefault("ledger", "data/ledger/ledger.jsonl")));
    if (!refusals.isEmpty()) {
      return refuse(out, refusals);
    }
    final String key = DataSets.sessionsKey(design.seedBase(), design.first(), design.sessions());
    final String looked = lookRefusal(ledger.entries(), key, prereg);
    if (looked != null) {
      return refuse(out, List.of(looked));
    }
    final Checkpoints cp = new Checkpoints(outDir);
    if (cp.failed() && !opts.containsKey("retry-failed")) {
      return refuse(out, List.of("the run in " + outDir + " has failed (" + cp.failureFile() + "); pass --retry-failed after reading it"));
    }
    cp.clearFailure();
    final List<Long> seeds = design.seeds();
    final Map<String, Object> manifest = manifest("registered", prereg, rows, design.arms(), seeds, opts, repo, git);
    cp.writeOrCheckManifest(manifest);
    cp.prepare();
    out.println(prereg.id() + ": " + cp.missing(seeds).size() + " of " + seeds.size() + " sessions to run, " + rows.size()
        + " band rows x " + design.arms().size() + " arms each");
    execute(cp, seeds, opts, out);
    final List<Long> missing = cp.missing(seeds);
    if (!missing.isEmpty()) {
      out.println("incomplete: " + missing.size() + " of " + seeds.size() + " registered sessions missing; nothing recorded");
      return INCOMPLETE;
    }
    return finish(prereg, design, rows, seeds, cp, ledger, key, git, out);
  }

  private static int refuse(final PrintStream out, final List<String> refusals) {
    out.println("refused:");
    for (final String r : refusals) {
      out.println("  " + r);
    }
    return REFUSED;
  }

  /** Earlier looks at the data set that forbid this run, or null. */
  static String lookRefusal(final List<Map<String, Object>> entries, final String key, final PreRegistration prereg) {
    for (final Map<String, Object> e : entries) {
      if (prereg.id().equals(e.get("prereg_id")) && key.equals(DataSets.key(e))) {
        return prereg.id() + " has already read data set " + key + " (ledger seq " + e.get("seq")
            + "); each pre-registration reads a data set once";
      }
    }
    return DataSets.refusal(entries, key, prereg, List.of());
  }

  /** Computes the summary once and appends the ledger row. */
  private static int finish(
      final PreRegistration prereg, final SessionDesign design, final List<BandParameters.Row> rows, final List<Long> seeds,
      final Checkpoints cp, final Ledger ledger, final String key, final GitState git, final PrintStream out) {
    final List<Map<String, Object>> before = ledger.entries();
    final String looked = lookRefusal(before, key, prereg);
    if (looked != null) {
      return refuse(out, List.of(looked));
    }
    final Map<Long, Map<String, Object>> sessions = cp.loadComplete(seeds);
    final List<String> labels = rows.stream().map(BandParameters.Row::label).toList();
    final Map<String, Object> results = ExperimentSummary.compute(design, labels, sessions);
    final Map<Long, Long> timings = cp.timings();
    final Map<String, Object> record = new LinkedHashMap<>();
    record.put("prereg_id", prereg.id());
    record.put("prereg_sha256", prereg.sha256());
    record.put("config_sha256", prereg.configSha256());
    record.put("design", "session_seeds");
    final Map<String, Object> sessionSeeds = new LinkedHashMap<>();
    sessionSeeds.put("base", design.seedBase());
    sessionSeeds.put("first", (long) design.first());
    sessionSeeds.put("count", (long) design.sessions());
    record.put("session_seeds", sessionSeeds);
    record.put("band_rows", labels);
    record.put("arms", design.arms());
    record.put("journal_sha256", ExperimentSummary.journalDigest(sessions));
    record.put("output_sha256", ExperimentSummary.outputDigest(sessions));
    record.put("code_commit", git.commit);
    record.put("code_dirty", git.dirty);
    record.put("scope", design.seedBase() == io.ratesalgo.sim.SessionSeeds.EXPLORATION ? "exploration" : "confirmation");
    record.put("look", ledger.looks(prereg.id(), String.valueOf(record.get("scope"))) + 1);
    record.put("recorded_utc", now().toString());
    final Map<String, Object> wall = new LinkedHashMap<>();
    long total = 0;
    for (final long s : seeds) {
      total += timings.getOrDefault(s, 0L);
    }
    wall.put("session_seconds_total", ExperimentSummary.round(total / 1000.0, 1));
    wall.put("mean_seconds_per_session", ExperimentSummary.round(total / 1000.0 / seeds.size(), 3));
    record.put("wall_clock", wall);
    record.put("results", results);
    record.put("guards_min", design.guardMinimums());
    record.put("verdict", design.descriptive() ? "description" : "pending_family_adjustment");
    DataSets.stamp(record, before, prereg);
    Checkpoints.writeAtomically(cp.summaryFile(), Json.write(record) + "\n");
    ledger.append(record);
    out.println(prereg.id() + ": " + seeds.size() + " sessions complete; recorded in " + ledger.file()
        + " (data set " + record.get("data_set") + ", look " + record.get("data_set_look") + ")");
    if (!design.descriptive()) {
      out.println("verdict pending: run LedgerCli --bh <family> for the Benjamini-Hochberg adjusted values");
    }
    return OK;
  }

  private static int pilot(final Map<String, String> opts, final PrintStream out) {
    final Path repo = Path.of(opts.getOrDefault("repo", ".")).toAbsolutePath().normalize();
    final PreRegistration prereg = PreRegistration.load(repo.resolve(require(opts, "prereg")));
    final SessionDesign design = SessionDesign.of(prereg, repo);
    final int n = Integer.parseInt(require(opts, "pilot"));
    if (n < 2) {
      return refuse(out, List.of("--pilot needs at least two sessions"));
    }
    if (design.pilotBase() == design.seedBase()) {
      return refuse(out, List.of("the pilot base equals the registered base"));
    }
    final List<String> arms =
        opts.containsKey("arms") ? Arrays.asList(opts.get("arms").split(",")) : List.of(design.baseline());
    final ArmFactory factory = ArmFactory.load(require(opts, "factory"));
    final List<String> refusals = new ArrayList<>();
    for (final String arm : arms) {
      final String r = factory.refusal(arm);
      if (r != null) {
        refusals.add("arm " + arm + ": " + r);
      }
    }
    final Path outDir = Path.of(require(opts, "out")).toAbsolutePath().normalize();
    final String insideGit = gitWorkTreeOf(outDir);
    if (insideGit != null) {
      refusals.add("the output directory " + outDir + " is inside the git work tree " + insideGit);
    }
    if (!refusals.isEmpty()) {
      return refuse(out, refusals);
    }
    final List<BandParameters.Row> rows = List.of(new BandParameters.Row(BandParameters.CENTRAL, SimParams.loadDefault()));
    final List<Long> seeds = SessionDesign.seeds(design.pilotBase(), 0, n);
    final Checkpoints cp = new Checkpoints(outDir);
    cp.clearFailure();
    cp.writeOrCheckManifest(manifest("pilot", prereg, rows, arms, seeds, opts, repo, GitState.of(repo, opts)));
    cp.prepare();
    out.println(prereg.id() + " pilot: " + cp.missing(seeds).size() + " of " + n + " sessions to run on pilot base "
        + design.pilotBase() + ", arms " + arms + ", central parameters only; no ledger row");
    final int workers = workers(opts);
    execute(cp, seeds, opts, out);
    if (!cp.missing(seeds).isEmpty()) {
      out.println("pilot incomplete: " + cp.missing(seeds).size() + " sessions missing");
      return INCOMPLETE;
    }
    final Map<String, Object> report = pilotReport(design, arms, cp.loadComplete(seeds), cp.timings(), workers);
    Checkpoints.writeAtomically(cp.dir().resolve("pilot.json"), Json.write(report) + "\n");
    printPilot(report, out);
    return OK;
  }

  /**
   * The pilot's measurements: session sd of P&amp;L in USD and in bp of the mean client DV01, the
   * sd of the paired difference (two arms) or its value at the design table's correlations (one
   * arm), the implied sessions, and wall-clock with a projection. The mean P&amp;L is used for the
   * variance only and is not reported.
   */
  static Map<String, Object> pilotReport(
      final SessionDesign design, final List<String> arms, final Map<Long, Map<String, Object>> sessions,
      final Map<Long, Long> timings, final int workers) {
    final Map<String, ExperimentSummary.Arm> collected = new LinkedHashMap<>();
    for (final String a : arms) {
      collected.put(a, ExperimentSummary.Arm.collect(sessions, BandParameters.CENTRAL, a));
    }
    final ExperimentSummary.Arm base = collected.get(arms.get(0));
    final double sd = Math.sqrt(Stats.variance(base.pnl));
    final double dv01 = base.meanClientDv01();
    final double effect = Double.isNaN(design.smallestEffectUsd()) ? Double.NaN : design.smallestEffectUsd();
    final Map<String, Object> r = new LinkedHashMap<>();
    r.put("pre_registration", design.id());
    r.put("pilot_base", design.pilotBase());
    r.put("sessions", (long) sessions.size());
    r.put("arms", arms);
    r.put("session_sd_usd", ExperimentSummary.round(sd, 2));
    r.put("session_sd_bp_of_client_dv01", dv01 > 0.0 ? ExperimentSummary.round(sd / dv01, 6) : "undefined");
    r.put("mean_client_dv01_usd", ExperimentSummary.round(dv01, 2));
    r.put("smallest_effect_usd", Double.isNaN(effect) ? "not stated" : effect);
    final double delta = Double.isNaN(effect) ? 24_000.0 : effect;
    r.put("effect_used_for_implied_n_usd", delta);
    final Map<String, Object> implied = new LinkedHashMap<>();
    if (arms.size() >= 2) {
      final double[] d = Stats.pairedDifferences(collected.get(arms.get(1)).pnl, base.pnl);
      final double sdD = Math.sqrt(Stats.variance(d));
      r.put("difference_sd_usd", ExperimentSummary.round(sdD, 2));
      implied.put("measured", PairedSessions.impliedSessions(sdD, delta));
    } else {
      for (final double rho : new double[] {0.0, 0.5, 0.9}) {
        final double sdD = sd * Math.sqrt(2.0 * (1.0 - rho));
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("difference_sd_usd", ExperimentSummary.round(sdD, 2));
        row.put("formula_sessions", (long) Math.ceil(PairedSessions.SESSIONS_PER_VARIANCE_RATIO * Math.pow(sdD / delta, 2)));
        row.put("with_floor", PairedSessions.impliedSessions(sdD, delta));
        implied.put("rho_" + rho, row);
      }
    }
    r.put("implied_sessions", implied);
    long total = 0;
    for (final long s : sessions.keySet()) {
      total += timings.getOrDefault(s, 0L);
    }
    final double perSession = total / 1000.0 / sessions.size();
    final double perArm = perSession / arms.size();
    r.put("workers", (long) workers);
    r.put("seconds_per_session_all_arms", ExperimentSummary.round(perSession, 2));
    r.put("seconds_per_session_per_arm", ExperimentSummary.round(perArm, 2));
    final int rows = 1 + 2 * design.bandParameters().size();
    final int registered = design.sessions() > 0 ? design.sessions() : PairedSessions.MINIMUM_CONFIRMATION_SESSIONS;
    final Map<String, Object> projection = new LinkedHashMap<>();
    projection.put(
        "hours_2000_sessions_per_arm_one_arm_central",
        ExperimentSummary.round(2000 * perArm / workers / 3600.0, 2));
    projection.put("design_sessions", (long) registered);
    projection.put("design_arms", (long) design.arms().size());
    projection.put("design_band_rows", (long) rows);
    projection.put(
        "hours_design_all_rows_all_arms",
        ExperimentSummary.round((double) registered * design.arms().size() * rows * perArm / workers / 3600.0, 1));
    r.put("projection", projection);
    r.put("note", "pilot: discarded for inference; mean P&L not reported; wall-clock measured on a shared machine");
    return r;
  }

  private static void printPilot(final Map<String, Object> report, final PrintStream out) {
    for (final Map.Entry<String, Object> e : report.entrySet()) {
      out.println(e.getKey() + ": " + Json.write(e.getValue()));
    }
  }

  static int status(final Checkpoints cp, final PrintStream out) {
    final Map<String, Object> manifest = cp.readManifest();
    final Map<String, Object> seeds = io.ratesalgo.backtest.util.Yaml.asMap(manifest.get("session_seeds"), "session_seeds");
    final long count = ((Number) seeds.get("count")).longValue();
    final Map<Long, Long> timings = cp.timings();
    long done = 0;
    long total = 0;
    final List<Long> registered =
        SessionDesign.seeds(((Number) seeds.get("base")).longValue(), ((Number) seeds.get("first")).intValue(), (int) count);
    for (final long s : registered) {
      if (cp.has(s)) {
        done++;
        total += timings.getOrDefault(s, 0L);
      }
    }
    final long workers = manifest.get("workers") instanceof Number w ? w.longValue() : 1L;
    out.println(manifest.get("kind") + " run of " + manifest.get("prereg_id") + ": " + done + " of " + count + " sessions done");
    if (done > 0) {
      final double mean = total / 1000.0 / done;
      out.println(String.format(java.util.Locale.ROOT, "mean wall-clock per session %.1f s; about %.1f h left at %d workers",
          mean, (count - done) * mean / workers / 3600.0, workers));
    }
    if (Files.isRegularFile(cp.heartbeatFile())) {
      out.println("heartbeat: " + read(cp.heartbeatFile()).trim());
    }
    if (cp.failed()) {
      out.println("FAILED: " + read(cp.failureFile()).trim());
      return ERROR;
    }
    if (Files.isRegularFile(cp.summaryFile())) {
      out.println("complete: summary written and ledger row appended");
    }
    return OK;
  }

  // ------------------------------------------------------------------ the pool

  private static int workers(final Map<String, String> opts) {
    return opts.containsKey("workers") ? Math.max(1, Integer.parseInt(opts.get("workers"))) : defaultWorkers();
  }

  /** Runs the missing sessions, in this process or in worker processes. */
  private static void execute(final Checkpoints cp, final List<Long> seeds, final Map<String, String> opts, final PrintStream out) {
    final List<Long> missing = cp.missing(seeds);
    if (opts.containsKey("in-process")) {
      for (final long s : missing) {
        SessionWorker.runOne(cp, s);
        cp.heartbeat(now() + " done " + (seeds.size() - cp.missing(seeds).size()) + "/" + seeds.size());
      }
      return;
    }
    new Pool(cp, workers(opts), opts.getOrDefault("worker-heap", "1536m"), out).run(missing, seeds.size());
  }

  /** A bounded pool of worker processes, one session each. */
  static final class Pool {
    private final Checkpoints cp;
    private final int workers;
    private final String heap;
    private final PrintStream out;

    Pool(final Checkpoints cp, final int workers, final String heap, final PrintStream out) {
      this.cp = cp;
      this.workers = workers;
      this.heap = heap;
      this.out = out;
    }

    private Process start(final long seed) {
      final String java = ProcessHandle.current().info().command().orElse(
          Path.of(System.getProperty("java.home"), "bin", "java").toString());
      final List<String> cmd = new ArrayList<>(List.of(java, "-Xmx" + heap,
          "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
          "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
          "--add-opens", "java.base/java.nio=ALL-UNNAMED",
          "-cp", System.getProperty("java.class.path"),
          SessionWorker.class.getName(), "--out", cp.dir().toString(), "--seed", Long.toString(seed)));
      try {
        return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(cp.logFile(seed).toFile()).start();
      } catch (final IOException e) {
        throw new UncheckedIOException("cannot start a worker for seed " + seed, e);
      }
    }

    void run(final List<Long> missing, final int registered) {
      final Deque<Long> queue = new ArrayDeque<>(missing);
      final Map<Long, Process> running = new LinkedHashMap<>();
      final Map<Long, Integer> attempts = new HashMap<>();
      boolean stop = false;
      while (!running.isEmpty() || (!stop && !queue.isEmpty())) {
        while (!stop && running.size() < workers && !queue.isEmpty()) {
          final long seed = queue.poll();
          attempts.merge(seed, 1, Integer::sum);
          running.put(seed, start(seed));
        }
        beat(running.size(), registered);
        final CompletableFuture<?>[] exits =
            running.values().stream().map(Process::onExit).toArray(CompletableFuture[]::new);
        try {
          CompletableFuture.anyOf(exits).get(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
          continue;
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          running.values().forEach(Process::destroy);
          throw new IllegalStateException("interrupted; finished sessions are kept, rerun to resume", e);
        } catch (final ExecutionException e) {
          throw new IllegalStateException(e);
        }
        for (final Map.Entry<Long, Process> e : new ArrayList<>(running.entrySet())) {
          if (e.getValue().isAlive()) {
            continue;
          }
          running.remove(e.getKey());
          final long seed = e.getKey();
          if (e.getValue().exitValue() == 0 && cp.has(seed)) {
            continue;
          }
          if (attempts.get(seed) < 2) {
            out.println("session " + seed + " failed (exit " + e.getValue().exitValue() + "); retrying once");
            queue.addFirst(seed);
          } else {
            cp.fail("session " + seed + " failed twice; see " + cp.logFile(seed));
            out.println("session " + seed + " failed twice; no new sessions are started (" + cp.logFile(seed) + ")");
            stop = true;
          }
        }
      }
      beat(0, registered);
    }

    private void beat(final int running, final int registered) {
      final long done = cp.timings().size();
      cp.heartbeat(now() + " running " + running + " workers; " + done + " session files of " + registered
          + " registered");
    }
  }

  // ------------------------------------------------------------------ helpers

  private static Map<String, Object> manifest(
      final String kind, final PreRegistration prereg, final List<BandParameters.Row> rows,
      final List<String> arms, final List<Long> seeds, final Map<String, String> opts, final Path repo, final GitState git) {
    final Path presets = repo.resolve(opts.getOrDefault("presets", "data/presets.yaml")).toAbsolutePath().normalize();
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", kind);
    m.put("prereg_id", prereg.id());
    m.put("prereg_sha256", prereg.sha256());
    m.put("config_sha256", prereg.configSha256());
    m.put("simulator_params_sha256", Hashing.sha256Text(SimParams.defaultParamsText()));
    m.put("factory", require(opts, "factory"));
    m.put("arms", arms);
    m.put("rows", rows.stream().map(BandParameters.Row::label).toList());
    final Map<String, Object> s = new LinkedHashMap<>();
    s.put("base", seeds.get(0) - seeds.get(0) % io.ratesalgo.sim.SessionSeeds.SPAN);
    s.put("first", seeds.get(0) % io.ratesalgo.sim.SessionSeeds.SPAN);
    s.put("count", (long) seeds.size());
    m.put("session_seeds", s);
    m.put("presets", presets.toString());
    m.put("presets_sha256", Hashing.sha256TextFile(presets));
    m.put("code_commit", git.commit);
    m.put("code_dirty", git.dirty);
    m.put("workers", (long) workers(opts));
    m.put("created_utc", now().toString());
    return m;
  }

  /** The git work tree that contains {@code dir} (or would, once created), or null. */
  static String gitWorkTreeOf(final Path dir) {
    Path p = dir;
    while (p != null) {
      if (Files.exists(p.resolve(".git"))) {
        return p.toString();
      }
      p = p.getParent();
    }
    return null;
  }

  /** The checkout's commit and whether it has uncommitted changes. */
  record GitState(String commit, boolean dirty) {
    static GitState of(final Path repo, final Map<String, String> opts) {
      final String commit = opts.containsKey("commit") ? opts.get("commit") : git(repo, "rev-parse", "HEAD").trim();
      final boolean dirty =
          opts.containsKey("dirty") ? Boolean.parseBoolean(opts.get("dirty")) : !git(repo, "status", "--porcelain").isBlank();
      return new GitState(commit.isEmpty() ? "unknown" : commit, dirty);
    }
  }

  private static String git(final Path repo, final String... args) {
    final List<String> cmd = new ArrayList<>();
    cmd.add("git");
    cmd.addAll(Arrays.asList(args));
    try {
      final Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
      try (InputStream in = p.getInputStream()) {
        final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return p.waitFor() == 0 ? text : "";
      }
    } catch (final IOException e) {
      return "";
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  private static Instant now() {
    return Instant.ofEpochSecond(SystemClock.INSTANCE.epochNanos() / 1_000_000_000L);
  }

  private static String read(final Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String require(final Map<String, String> opts, final String key) {
    final String v = opts.get(key);
    if (v == null) {
      throw new PreRegistrationException(List.of("--" + key + " is required"));
    }
    return v;
  }

  /** {@code --name value} pairs from {@code from}; a flag with no value ({@code --allow-dirty}) maps to "true". */
  static Map<String, String> parse(final String[] args, final int from) {
    final Map<String, String> opts = new LinkedHashMap<>();
    for (int i = from; i < args.length; i++) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("expected --name [value], got " + args[i]);
      }
      final String name = args[i].substring(2);
      if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
        opts.put(name, args[++i]);
      } else {
        opts.put(name, "true");
      }
    }
    return opts;
  }
}
