package io.ratesalgo.ops.process;

import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.ops.config.JsonSchema;
import io.ratesalgo.ops.config.ProcessConfig;
import io.ratesalgo.ops.http.OpsHttpServer;
import io.ratesalgo.ops.metrics.MetricNames;
import io.ratesalgo.ops.metrics.MetricsRegistry;
import io.ratesalgo.posttrade.recon.BreakClass;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point of the trading and venue-simulator images and of the process-mode rehearsal.
 *
 * <pre>
 * OpsProcessMain --config deploy/config/trading-blue.yaml --schema deploy/config/schema/process-config.schema.json
 * </pre>
 *
 * <p>Validates the configuration first and exits 2 if it is invalid. Role {@code trading} opens
 * the journal, starts the simulated quoter (P3's engine at integration) and serves the health,
 * metrics and control endpoints. Role {@code venue-sim} serves health and metrics only, as the
 * placeholder the coordinator replaces with P2's simulator main. The process runs until it is
 * terminated.
 */
public final class OpsProcessMain {

  private OpsProcessMain() {}

  /** A started process: its server, quoter (null for venue-sim) and journal. */
  public record Started(OpsHttpServer server, SimulatedQuoter quoter, JournalWriter journal, MetricsRegistry metrics)
      implements AutoCloseable {
    @Override
    public void close() {
      server.close();
      if (journal != null) {
        journal.close();
      }
    }
  }

  public static void main(final String[] args) throws IOException, InterruptedException {
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

  /** Validates the configuration and starts the process; null if the configuration is invalid. */
  public static Started start(final String[] args, final Map<String, String> env, final PrintStream out,
      final PrintStream err) throws IOException {
    String config = null;
    String schemaPath = null;
    for (int i = 0; i + 1 < args.length; i += 2) {
      switch (args[i]) {
        case "--config" -> config = args[i + 1];
        case "--schema" -> schemaPath = args[i + 1];
        default -> {
          err.println("unknown argument " + args[i]);
          return null;
        }
      }
    }
    if (config == null || schemaPath == null) {
      err.println("usage: OpsProcessMain --config FILE --schema FILE");
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

    final MetricsRegistry metrics = new MetricsRegistry();
    metrics.gauge(MetricNames.PROCESS_INFO, "Process identity", "name", cfg.name(), "role", cfg.role(),
        "colour", cfg.colour()).set(1);
    metrics.gauge(MetricNames.QUEUE_DEPTH, "Messages waiting in a ring buffer", "queue", "inbound");
    metrics.gauge(MetricNames.QUEUE_DEPTH, "Messages waiting in a ring buffer", "queue", "outbound");
    for (final String stage : new String[] {"decode", "price", "risk", "encode"}) {
      metrics.histogram(MetricNames.STAGE_LATENCY, "Seconds spent in one stage of the event loop",
          MetricNames.latencyBuckets(), "stage", stage);
    }
    for (final BreakClass c : BreakClass.values()) {
      for (final String explained : new String[] {"true", "false"}) {
        metrics.gauge(MetricNames.RECON_BREAKS, "Breaks in the latest reconciliation", "class",
            c.name().toLowerCase(Locale.ROOT), "explained", explained);
      }
    }

    if (!"trading".equals(cfg.role())) {
      final OpsHttpServer server = new OpsHttpServer(cfg.bindAddress(), cfg.port(), metrics, () -> null,
          new OpsHttpServer.Control() {
            @Override
            public void kill(final int requestedBy) {
              // a venue simulator has nothing to kill
            }

            @Override
            public String statusJson() {
              return "{\"role\":\"" + cfg.role() + "\"}";
            }
          }, null);
      out.println(cfg.name() + " (" + cfg.role() + ") listening on " + cfg.bindAddress() + ":" + server.port());
      return new Started(server, null, null, metrics);
    }

    final Path dir = Path.of(cfg.journalDirectory());
    Files.createDirectories(dir);
    final Path file = dir.resolve(cfg.name() + "-" + System.currentTimeMillis() + ".raj");
    final JournalWriter journal = JournalWriter.create(file, cfg.journalCapacityBytes(), SystemClock.INSTANCE);
    final SimulatedQuoter quoter =
        new SimulatedQuoter(journal, SystemClock.INSTANCE, cfg.instruments(), cfg.quotingEnabledAtStart());
    metrics.gauge(MetricNames.QUOTES_LIVE, "Quotes live at venues").bind(quoter::liveQuotes);
    metrics.gauge(MetricNames.KILL_SWITCH, "1 when the kill switch is engaged").bind(() -> quoter.killed() ? 1 : 0);
    metrics.gauge(MetricNames.QUOTING_ENABLED, "1 when quoting is enabled").bind(() -> quoter.quoting() ? 1 : 0);
    metrics.gauge(MetricNames.LIMIT_UTILISATION, "Observed over limit", "limit", "max_messages_per_second").set(0);
    final MetricsRegistry.Counter frames = metrics.counter(MetricNames.JOURNAL_FRAMES, "Journal frames appended");
    final String token = cfg.controlTokenEnv() == null ? null : env.get(cfg.controlTokenEnv());
    final OpsHttpServer server = new OpsHttpServer(cfg.bindAddress(), cfg.port(), metrics,
        () -> journal.remaining() > 0 ? null : "journal full", new OpsHttpServer.Control() {
          @Override
          public void kill(final int requestedBy) {
            final long before = quoter.frames();
            quoter.kill(requestedBy);
            frames.add(quoter.frames() - before);
          }

          @Override
          public String statusJson() {
            return quoter.statusJson();
          }
        }, token);
    out.println(cfg.name() + " (" + cfg.role() + ", " + cfg.colour() + ") listening on " + cfg.bindAddress() + ":"
        + server.port() + ", journal " + file);
    return new Started(server, quoter, journal, metrics);
  }
}
