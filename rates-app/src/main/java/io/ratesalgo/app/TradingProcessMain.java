package io.ratesalgo.app;

import io.ratesalgo.app.gateway.ReconnectPolicy;
import io.ratesalgo.app.loop.DeskLoopFactory;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.core.LoopRecovery;
import io.ratesalgo.core.LoopReplay;
import io.ratesalgo.core.ReplayResult;
import io.ratesalgo.ops.config.JsonSchema;
import io.ratesalgo.ops.config.ProcessConfig;
import io.ratesalgo.ops.http.OpsHttpServer;
import io.ratesalgo.ops.metrics.MetricNames;
import io.ratesalgo.ops.metrics.MetricsRegistry;
import io.ratesalgo.router.FrameSink;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point of the trading process (API_SPEC.md Section 18.1).
 *
 * <pre>
 * TradingProcessMain --config deploy/config/trading-blue.yaml --schema deploy/config/schema/process-config.schema.json
 *     [--seed 7] [--time-scale 10]
 * TradingProcessMain --replay JOURNAL_DIR
 * </pre>
 *
 * <p>Run mode validates the rates-ops process configuration (exit 2 if it is refused), starts the
 * trading process with its simulators ({@link TradingProcess}), runs the loops on their own threads
 * with simulated time paced at {@code --time-scale} times real time from 08:30 New York, and serves
 * health, readiness, metrics and the kill switch on the configured port until it is terminated. A
 * bind address that is not loopback is refused, like any invalid configuration, unless the control
 * token and {@code http.tls} are set, and then the endpoints are served over HTTPS; the loop
 * threads' idle strategy and CPU come from the optional {@code runtime} section. The optional
 * {@code fix} section sets TLS and logon credentials for the FIX sessions (and for the in-process
 * simulators that accept them), whether sequence numbers resume or reset at logon, and the
 * reconnect policy; a password is named there by environment variable or file and read here.
 * Started on a journal directory that already holds journals, the process rebuilds itself from
 * them before it connects and prints what it found ({@link TradingProcess}). A
 * process with role {@code venue-sim} serves health and metrics only: the simulators run inside
 * the trading process. Replay mode replays each group's journals in {@code JOURNAL_DIR} through the
 * desk and prints whether the outbound hash matches (exit 0) or not (exit 1).
 */
public final class TradingProcessMain {

  private TradingProcessMain() {}

  /** A started process with its server; closing stops both. */
  public record Started(TradingProcess process, OpsHttpServer server) implements AutoCloseable {
    @Override
    public void close() {
      server.close();
      if (process != null) {
        process.close();
      }
    }
  }

  public static void main(final String[] args) throws IOException, InterruptedException {
    if (args.length == 2 && "--replay".equals(args[0])) {
      System.exit(replay(Path.of(args[1]), System.out));
      return;
    }
    final Started s = start(args, System.getenv(), System.out, System.err);
    if (s == null) {
      System.exit(2);
      return;
    }
    final CountDownLatch done = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      s.close();
      done.countDown();
    }, "shutdown"));
    done.await();
  }

  /**
   * Replays every group directory under {@code journalDir} (a directory holding {@code in} and
   * {@code out} journals) and prints one line per group. Returns 0 if every group matches.
   */
  public static int replay(final Path journalDir, final PrintStream out) throws IOException {
    int failures = 0;
    int groups = 0;
    try (var dirs = Files.list(journalDir)) {
      for (final Path dir : dirs.sorted().toList()) {
        if (!Files.isRegularFile(dir.resolve("in.000001.raj"))) {
          continue;
        }
        groups++;
        final ReplayResult r = replayGroup(dir);
        out.println(String.format(Locale.ROOT,
            "%s: %d inbound frames, %d outbound produced, %d recorded, hash %016x vs %016x: %s",
            dir.getFileName(), r.inboundFrames(), r.outboundProduced(), r.outboundRecorded(), r.replayHash(),
            r.recordedHash(), r.matches() ? "match" : "MISMATCH at " + r.firstMismatch()));
        if (!r.matches()) {
          failures++;
        }
      }
    }
    if (groups == 0) {
      out.println("no group journals under " + journalDir);
      return 1;
    }
    return failures == 0 ? 0 : 1;
  }

  /** Replays one group's journals through a freshly built desk. */
  public static ReplayResult replayGroup(final Path groupDir) {
    return LoopReplay.run(groupDir, "in", "out",
        new DeskLoopFactory(UsdUniverse.standard(), DeskSettings.defaults(), FrameSink.NONE));
  }

  /** Validates the configuration and starts the process; null if the configuration is refused. */
  public static Started start(final String[] args, final Map<String, String> env, final PrintStream out,
      final PrintStream err) throws IOException {
    String config = null;
    String schemaPath = null;
    long seed = 7L;
    double timeScale = 10.0;
    for (int i = 0; i + 1 < args.length; i += 2) {
      switch (args[i]) {
        case "--config" -> config = args[i + 1];
        case "--schema" -> schemaPath = args[i + 1];
        case "--seed" -> seed = Long.parseLong(args[i + 1]);
        case "--time-scale" -> timeScale = Double.parseDouble(args[i + 1]);
        default -> {
          err.println("unknown argument " + args[i]);
          return null;
        }
      }
    }
    if (config == null || schemaPath == null) {
      err.println("usage: TradingProcessMain --config FILE --schema FILE [--seed N] [--time-scale X] | --replay DIR");
      return null;
    }
    final ProcessConfig cfg;
    try {
      final JsonSchema schema = ProcessConfig.schema(Path.of(schemaPath));
      cfg = ProcessConfig.load(Path.of(config), schema, env);
    } catch (final ProcessConfig.InvalidConfigException e) {
      err.println("configuration refused, process not started:");
      e.errors().forEach(x -> err.println("  " + x));
      return null;
    }
    final String bindProblem = cfg.controlBindProblem(env);
    if (bindProblem != null) {
      err.println("configuration refused, process not started:");
      err.println("  " + bindProblem);
      return null;
    }
    final String securityProblem = cfg.securityProblem(env);
    if (securityProblem != null) {
      err.println("configuration refused, process not started:");
      err.println("  " + securityProblem);
      return null;
    }
    final TlsConfig httpTls = cfg.httpTls(env);
    final String token = cfg.controlToken(env);
    final MetricsRegistry metrics = new MetricsRegistry();
    metrics.gauge(MetricNames.PROCESS_INFO, "Process identity", "name", cfg.name(), "role", cfg.role(),
        "colour", cfg.colour()).set(1);
    if (!"trading".equals(cfg.role())) {
      final OpsHttpServer server = new OpsHttpServer(cfg.bindAddress(), cfg.port(), metrics, () -> null,
          new OpsHttpServer.Control() {
            @Override
            public String kill(final int requestedBy) {
              // the simulators run inside the trading process; nothing to kill here
              return null;
            }

            @Override
            public String statusJson() {
              return "{\"role\":\"" + cfg.role() + "\"}";
            }
          }, token, httpTls);
      out.println(cfg.name() + " (" + cfg.role() + ") listening on " + cfg.bindAddress() + ":" + server.port());
      return new Started(null, server);
    }
    final Path journalDir = Path.of(cfg.journalDirectory());
    Files.createDirectories(journalDir);
    final TradingProcess.Fix fix =
        new TradingProcess.Fix(
            cfg.fixClientTls(env), cfg.fixAcceptorTls(env), cfg.fixUsername(), cfg.fixPassword(env),
            cfg.fixResetOnLogon(),
            new ReconnectPolicy(
                cfg.fixReconnectMaxAttempts(), cfg.fixReconnectInitialBackoffNanos(), cfg.fixReconnectMaxBackoffNanos()));
    final TradingProcess process;
    try {
      process =
          TradingProcess.start(
              new TradingProcess.Options(journalDir, seed, DeskSettings.defaults())
                  .withTuning(tuning(cfg.runtime()))
                  .withFix(fix));
    } catch (final io.ratesalgo.journal.JournalCorruptionException | LoopRecovery.RecoveryException e) {
      // the journals do not allow a restart: say why and stay down
      err.println("restart refused, process not started:");
      err.println("  " + e.getMessage());
      return null;
    }
    out.println("runtime: " + cfg.runtime());
    // what a restart found: frames replayed, a torn tail cut, how long the process was down
    process.recoveryLog().forEach(line -> out.println("recovery: " + line));
    final OpsHttpServer server;
    try {
      server = serve(process, metrics, cfg.bindAddress(), cfg.port(), token, httpTls);
    } catch (final IOException | RuntimeException e) {
      process.close();
      throw e;
    }
    process.startThreads(timeScale);
    out.println(cfg.name() + " (" + cfg.role() + ", " + cfg.colour() + ") listening on " + cfg.bindAddress() + ":"
        + server.port() + ", journals " + journalDir + ", simulated time x" + timeScale);
    return new Started(process, server);
  }

  /** Binds the rates-ops endpoints to a process: readiness, metrics, status and the kill switch. */
  public static OpsHttpServer serve(final TradingProcess process, final MetricsRegistry metrics, final String bind,
      final int port, final String token) throws IOException {
    return serve(process, metrics, bind, port, token, null);
  }

  /** As {@link #serve(TradingProcess, MetricsRegistry, String, int, String)}, over HTTPS when {@code tls} is given. */
  public static OpsHttpServer serve(final TradingProcess process, final MetricsRegistry metrics, final String bind,
      final int port, final String token, final TlsConfig tls) throws IOException {
    metrics.gauge(MetricNames.QUOTES_LIVE, "Quotes live at venues").bind(process::liveQuotes);
    metrics.gauge(MetricNames.KILL_SWITCH, "1 when the kill switch is engaged")
        .bind(() -> process.killSwitchEngaged() ? 1 : 0);
    metrics.gauge(MetricNames.QUOTING_ENABLED, "1 when quoting is enabled")
        .bind(() -> process.killSwitchEngaged() ? 0 : 1);
    return new OpsHttpServer(bind, port, metrics, process::notReadyReason, new OpsHttpServer.Control() {
      @Override
      public String kill(final int requestedBy) {
        return process.kill(requestedBy).reason();
      }

      @Override
      public String statusJson() {
        return process.statusJson();
      }
    }, token, tls);
  }

  /**
   * The process tuning for a configuration's {@code runtime} section, its profile already applied
   * (DECISIONS.md DR-R6-L02). The kill deadline and the supervisor's limits stay at their defaults.
   */
  static TradingProcess.Tuning tuning(final ProcessConfig.RuntimeSettings r) {
    final io.ratesalgo.core.LoopJournals.Settings journals =
        io.ratesalgo.core.LoopJournals.Settings.DEFAULT
            .withMode(io.ratesalgo.core.JournalMode.valueOf(r.journalMode().toUpperCase(java.util.Locale.ROOT)))
            .withHashAlgorithm(io.ratesalgo.journal.ChainHash.byName(r.journalHash()))
            .withRing(io.ratesalgo.journal.RingJournal.DEFAULT_RING_CAPACITY, r.journalIdleStrategy())
            .withSegmentOptions(io.ratesalgo.journal.SegmentedJournal.Options.DEFAULT.withLockPages(r.lockJournalPages()));
    return TradingProcess.Tuning.DEFAULT
        .withLoopThread(r.loopIdleStrategy(), r.loopCpu())
        .withGatewayThreads(r.gatewayIdleStrategy(), r.gatewayCpu())
        .withJournals(journals)
        .withCurveConflation(r.conflateCurveInputs());
  }
}
