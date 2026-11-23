package io.ratesalgo.bench.wired;

import io.ratesalgo.app.TradingProcess;
import io.ratesalgo.app.gateway.ReconnectPolicy;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.bench.LatencyReport;
import io.ratesalgo.bench.MachineInfo;
import io.ratesalgo.common.security.KeytoolCertificates;
import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.core.JournalMode;
import io.ratesalgo.core.LoopCounters;
import io.ratesalgo.core.LoopProbe;
import io.ratesalgo.fix.gateway.GatewayProbe;
import io.ratesalgo.venues.Venues;
import java.io.IOException;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.HdrHistogram.Histogram;

/**
 * The wired-desk latency benchmark (docs/BENCHMARKS_V2.md, review finding L4): the real trading
 * process, {@link TradingProcess} with its DeskGroup pricing, risk, quoting and hedging, its
 * journals, its FIX sessions and its threads, answering QuoteRequests that a load generator sends
 * over loopback FIX on a fixed schedule. One configuration per JVM; {@link WiredDeskSuite} runs
 * them all.
 *
 * <p>What is measured, per request, all from {@link System#nanoTime()} in this one JVM:
 *
 * <ul>
 *   <li>T0, the request's scheduled send time; every latency starts here, so a stall anywhere,
 *       the generator's included, shows in every request it delays (no coordinated omission);
 *   <li>the generator's write returned (its lateness is reported, never used to drop a run);
 *   <li>inside the desk ({@link StageProbe}): request published to the loop's ring, read by the
 *       loop, journaled, handled; Quote taken from the outbound ring, encoded, written;
 *   <li>T4, the Quote's bytes written by the desk; T5, the Quote read by the generator.
 * </ul>
 *
 * <p>Headline: T0 to T4, scheduled to quote written. Every run is kept and reported. The CLOB
 * simulator, its book feed, the curve feed and the venues' threads run in the same process, as they
 * do in the deployed simulator setup; their work and their garbage are part of what the desk
 * competes with. Not measured: a NIC, kernel bypass, a real venue, another machine.
 */
public final class WiredDeskBenchmark {

  private WiredDeskBenchmark() {}

  /** One configuration. */
  record Config(
      String profile, String journal, String transport, int rate, int warmupSeconds, int measureSeconds,
      String label, Path out) {

    static Config parse(final String[] args) {
      final Map<String, String> a = new LinkedHashMap<>();
      for (final String arg : args) {
        if (!arg.startsWith("--") || !arg.contains("=")) {
          throw new IllegalArgumentException("arguments are --name=value: " + arg);
        }
        a.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
      }
      final Config c =
          new Config(
              a.getOrDefault("profile", "default"),
              a.getOrDefault("journal", "direct"),
              a.getOrDefault("transport", "plain"),
              Integer.parseInt(a.getOrDefault("rate", "1000")),
              Integer.parseInt(a.getOrDefault("warmup-seconds", "20")),
              Integer.parseInt(a.getOrDefault("measure-seconds", "60")),
              a.getOrDefault("label", "local"),
              Path.of(a.getOrDefault("out", "target/wired")));
      if (!List.of("default", "lowLatency").contains(c.profile())
          || !List.of("direct", "ring").contains(c.journal())
          || !List.of("plain", "tls").contains(c.transport())
          || c.rate() < 1 || c.rate() > 100_000 || c.warmupSeconds() < 0 || c.measureSeconds() < 1) {
        throw new IllegalArgumentException("bad configuration " + c);
      }
      return c;
    }

    String name() {
      return profile + "-" + journal + "-" + transport;
    }
  }

  public static void main(final String[] args) throws Exception {
    final Path report = run(Config.parse(args));
    System.out.println("report: " + report);
  }

  /** The tuning of a configuration: its profile, with the journal mode it names. */
  static TradingProcess.Tuning tuning(final Config c) {
    final TradingProcess.Tuning base =
        "lowLatency".equals(c.profile()) ? TradingProcess.Tuning.LOW_LATENCY : TradingProcess.Tuning.DEFAULT;
    return base.withJournals(base.journals().withMode(JournalMode.valueOf(c.journal().toUpperCase(Locale.ROOT))));
  }

  /** Runs one configuration and writes its JSON report; returns the report's path. */
  static Path run(final Config c) throws Exception {
    if (!LoopProbe.ENABLED || !GatewayProbe.ENABLED) {
      throw new IllegalStateException("start the JVM with -Drates.probes=true: the stage stamps are off");
    }
    final MachineInfo machine = MachineInfo.detect();
    final Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    final long clockResolution = clockResolutionNanos();
    final int warm = c.warmupSeconds() * c.rate();
    final int total = warm + c.measureSeconds() * c.rate();
    final long interval = 1_000_000_000L / c.rate();
    final Path work = Files.createTempDirectory("rates-wired-");
    final StageProbe probe = new StageProbe(FixLoadGenerator.FIRST_RFQ_ID, total);
    LoopProbe.install(probe);
    GatewayProbe.install(probe);

    TlsConfig serverTls = null;
    TradingProcess.Fix fix = TradingProcess.Fix.SIMULATOR;
    if ("tls".equals(c.transport())) {
      final Secret password = Secret.of("bench-store-pass-" + System.nanoTime());
      final KeytoolCertificates.Identity id = KeytoolCertificates.localhost(work, "bench", password, 2);
      serverTls = TlsConfig.builder().keyStore(id.keyStore(), password).build();
      final TlsConfig clientTls = TlsConfig.builder().trustStore(id.trustStore(), password).build();
      fix = new TradingProcess.Fix(clientTls, serverTls, null, null, true, ReconnectPolicy.DEFAULT);
    }
    final String[] securities =
        Stream.concat(Venues.swaps().stream(), Venues.treasuries().stream())
            .map(i -> i.securityId())
            .toArray(String[]::new);
    final long[] scheduled = new long[total];
    final TradingProcess[] process = new TradingProcess[1];
    final long[] gcBefore = new long[2];
    final long[] gcAfter = new long[2];
    final long[] jitMillis = new long[2];
    final Map<String, Long> counters = new LinkedHashMap<>();
    try (FixLoadGenerator gen =
        new FixLoadGenerator(serverTls, AppIds.COMP_ID, securities,
            () -> process[0] == null ? System.currentTimeMillis() * 1_000_000L : process[0].clock().epochNanos(), total)) {
      gen.acceptInBackground();
      final Object go = new Object();
      final boolean[] start = new boolean[1];
      final Throwable[] genFailure = new Throwable[1];
      final Thread generator =
          new Thread(
              () -> {
                try {
                  if (!gen.awaitLogon(TimeUnit.SECONDS.toNanos(60))) {
                    throw new IllegalStateException("the desk did not log on to the load generator");
                  }
                  while (true) {
                    synchronized (go) {
                      if (start[0]) {
                        break;
                      }
                    }
                    gen.idle(1_000_000L);
                  }
                  final long t0 = System.nanoTime() + 1_000_000L;
                  gen.run(0, warm, t0, interval, scheduled);
                  sample(gcBefore, jitMillis, 0);
                  gen.run(warm, total, t0 + warm * interval, interval, scheduled);
                  gen.idle(TimeUnit.SECONDS.toNanos(2)); // the last quotes and passes
                  sample(gcAfter, jitMillis, 1);
                } catch (final Throwable t) {
                  genFailure[0] = t;
                }
              },
              "loadgen");
      generator.setDaemon(true);
      generator.start();

      final TradingProcess.Options options =
          new TradingProcess.Options(work.resolve("journal"), 7L, DeskSettings.defaults())
              .withTuning(tuning(c))
              .withFix(fix)
              .withD2cEndpoint(new TradingProcess.Endpoint("127.0.0.1", gen.port(), "D2C"));
      process[0] = TradingProcess.start(options);
      final TradingProcess p = process[0];
      try {
        p.startThreads(1.0);
        final long readyBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (p.notReadyReason() != null) {
          if (System.nanoTime() - readyBy > 0) {
            throw new IllegalStateException("desk not ready: " + p.notReadyReason());
          }
          Thread.sleep(50);
        }
        synchronized (go) {
          start[0] = true;
        }
        generator.join(TimeUnit.SECONDS.toMillis(c.warmupSeconds() + c.measureSeconds() + 120L));
        if (generator.isAlive()) {
          throw new IllegalStateException("the load generator did not finish");
        }
        if (genFailure[0] != null) {
          throw new IllegalStateException("load generator failed", genFailure[0]);
        }
        final TradingProcess.Group g = p.group(0);
        counters.put("quotesSent", g.quotesSent());
        counters.put("conflated", g.loop().counters().get(LoopCounters.CONFLATED));
        counters.put("outboundDropped", g.loop().counters().get(LoopCounters.OUTBOUND_DROPPED));
        counters.put("journalProducerWaits", g.journals().producerWaits());
        counters.put("journalRollWaits", g.journals().rollWaits());
        counters.put("journalBacklogHighWaterBytes", g.journals().backlogHighWater());
        counters.put("journalPagesLocked", g.journals().pageLocks()[0]);
        counters.put("journalPageLocksRefused", g.journals().pageLocks()[1]);
        counters.put("haltReasonPresent", p.haltReason() == null ? 0L : 1L);
        counters.put("generatorQuotes", gen.quotes());
        counters.put("generatorDuplicateQuotes", gen.duplicateQuotes());
        counters.put("loopPinned", p.loopRunners().get(0).isPinned() ? 1L : 0L);
      } finally {
        p.close();
      }
      return write(c, machine, started, clockResolution, gen, probe, scheduled, warm, total, gcBefore, gcAfter,
          jitMillis, counters);
    } finally {
      deleteTree(work);
    }
  }

  private static void sample(final long[] gc, final long[] jit, final int slot) {
    long count = 0;
    long millis = 0;
    for (final GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
      count += Math.max(0, b.getCollectionCount());
      millis += Math.max(0, b.getCollectionTime());
    }
    gc[0] = count;
    gc[1] = millis;
    final CompilationMXBean compiler = ManagementFactory.getCompilationMXBean();
    jit[slot] = compiler != null && compiler.isCompilationTimeMonitoringSupported() ? compiler.getTotalCompilationTime() : -1;
  }

  /** The smallest step of {@code System.nanoTime} seen over many reads. */
  static long clockResolutionNanos() {
    long best = Long.MAX_VALUE;
    for (int i = 0; i < 200_000; i++) {
      final long a = System.nanoTime();
      long b;
      do {
        b = System.nanoTime();
      } while (b == a);
      best = Math.min(best, b - a);
    }
    return best;
  }

  /** Interval names and the stage columns (or -1 for T0, -2 for the generator's write, -3 for T5) they span. */
  static final String[][] INTERVALS = {
    {"scheduledToQuoteWritten", "T0", "WRITTEN"},
    {"scheduledToQuoteReceived", "T0", "T5"},
    {"generatorLateness", "T0", "SENT"},
    {"wireInToPublished", "SENT", "PUBLISHED"},
    {"ringWait", "PUBLISHED", "LOOP_READ"},
    {"journalAppend", "LOOP_READ", "JOURNALED"},
    {"handler", "JOURNALED", "HANDLED"},
    {"outboundRingWait", "HANDLED", "DEQUEUED"},
    {"encode", "DEQUEUED", "ENCODED"},
    {"finishAndSocketWrite", "ENCODED", "WRITTEN"},
    {"wireOut", "WRITTEN", "T5"},
  };

  private static long stamp(
      final String point, final int k, final long[] scheduled, final FixLoadGenerator gen, final StageProbe probe) {
    return switch (point) {
      case "T0" -> scheduled[k];
      case "SENT" -> gen.sentNanos(k);
      case "T5" -> gen.receivedNanos(k);
      case "PUBLISHED" -> probe.at(StageProbe.PUBLISHED, k);
      case "LOOP_READ" -> probe.at(StageProbe.LOOP_READ, k);
      case "JOURNALED" -> probe.at(StageProbe.JOURNALED, k);
      case "HANDLED" -> probe.at(StageProbe.HANDLED, k);
      case "DEQUEUED" -> probe.at(StageProbe.DEQUEUED, k);
      case "ENCODED" -> probe.at(StageProbe.ENCODED, k);
      case "WRITTEN" -> probe.at(StageProbe.WRITTEN, k);
      default -> throw new IllegalArgumentException(point);
    };
  }

  private static Path write(
      final Config c, final MachineInfo machine, final Instant started, final long clockResolution,
      final FixLoadGenerator gen, final StageProbe probe, final long[] scheduled, final int warm, final int total,
      final long[] gcBefore, final long[] gcAfter, final long[] jitMillis, final Map<String, Long> counters)
      throws IOException {
    final Map<String, Histogram> hist = new LinkedHashMap<>();
    for (final String[] iv : INTERVALS) {
      hist.put(iv[0], new Histogram(1, TimeUnit.SECONDS.toNanos(60), 3));
    }
    long answered = 0;
    long unanswered = 0;
    for (int k = warm; k < total; k++) {
      if (gen.receivedNanos(k) == 0 || probe.at(StageProbe.WRITTEN, k) == 0) {
        unanswered++;
        if (gen.sentNanos(k) != 0) {
          hist.get("generatorLateness").recordValue(Math.max(1, gen.sentNanos(k) - scheduled[k]));
        }
        continue;
      }
      answered++;
      for (final String[] iv : INTERVALS) {
        final long from = stamp(iv[1], k, scheduled, gen, probe);
        final long to = stamp(iv[2], k, scheduled, gen, probe);
        if (from != 0 && to != 0) {
          hist.get(iv[0]).recordValue(Math.max(1, Math.min(to - from, TimeUnit.SECONDS.toNanos(60))));
        }
      }
    }
    Files.createDirectories(c.out());
    final String stampText = started.toString().replace(":", "").replace("-", "");
    final Path json = c.out().resolve("wired-" + c.name() + "-" + stampText + ".json");
    final StringBuilder b = new StringBuilder(16_384);
    b.append("{\n  \"schema\": \"rates-bench-wired/1\",\n");
    b.append("  \"label\": ").append(quote(c.label())).append(",\n");
    b.append("  \"configuration\": ").append(quote(c.name())).append(",\n");
    b.append("  \"started\": ").append(quote(started.toString())).append(",\n");
    b.append("  \"commit\": ").append(quote(machine.commit())).append(",\n");
    b.append("  \"machine\": {\"model\": ").append(quote(machine.model())).append(", \"cpu\": ")
        .append(quote(machine.cpu())).append(", \"logicalCpus\": ").append(machine.logicalCpus())
        .append(", \"memoryBytes\": ").append(machine.memoryBytes()).append(", \"os\": ").append(quote(machine.os()))
        .append(", \"arch\": ").append(quote(machine.arch())).append("},\n");
    b.append("  \"jvm\": {\"version\": ").append(quote(machine.jvm())).append(", \"flags\": [");
    for (int i = 0; i < machine.jvmFlags().size(); i++) {
      b.append(i == 0 ? "" : ", ").append(quote(machine.jvmFlags().get(i)));
    }
    b.append("]},\n");
    b.append("  \"clockResolutionNs\": ").append(clockResolution).append(",\n");
    b.append("  \"config\": {\"profile\": ").append(quote(c.profile())).append(", \"journal\": ")
        .append(quote(c.journal())).append(", \"transport\": ").append(quote(c.transport()))
        .append(", \"rate\": ").append(c.rate()).append(", \"warmupSeconds\": ").append(c.warmupSeconds())
        .append(", \"measureSeconds\": ").append(c.measureSeconds()).append(", \"tuning\": ")
        .append(quote(String.valueOf(tuning(c)))).append("},\n");
    b.append("  \"counts\": {\"requestsMeasured\": ").append(total - warm).append(", \"answered\": ").append(answered)
        .append(", \"unanswered\": ").append(unanswered).append(", \"gcCollectionsMeasured\": ")
        .append(gcAfter[0] - gcBefore[0]).append(", \"gcMillisMeasured\": ").append(gcAfter[1] - gcBefore[1])
        .append(", \"jitMillisMeasured\": ")
        .append(jitMillis[0] < 0 ? -1 : jitMillis[1] - jitMillis[0]);
    for (final Map.Entry<String, Long> e : counters.entrySet()) {
      b.append(", ").append(quote(e.getKey())).append(": ").append(e.getValue());
    }
    b.append("},\n");
    final List<String> names = new ArrayList<>(hist.keySet());
    for (int i = 0; i < names.size(); i++) {
      b.append("  ").append(quote(names.get(i))).append(": ").append(LatencyReport.intervalJson(hist.get(names.get(i))))
          .append(i == names.size() - 1 ? "\n" : ",\n");
    }
    b.append("}\n");
    Files.writeString(json, b.toString(), StandardCharsets.UTF_8);
    System.out.println(summary(c, hist, answered, unanswered));
    return json;
  }

  /** A one-paragraph console summary of a run. */
  static String summary(
      final Config c, final Map<String, Histogram> hist, final long answered, final long unanswered) {
    final StringBuilder b = new StringBuilder();
    b.append(c.name()).append(": answered ").append(answered).append(", unanswered ").append(unanswered).append('\n');
    for (final Map.Entry<String, Histogram> e : hist.entrySet()) {
      final Histogram h = e.getValue();
      if (h.getTotalCount() == 0) {
        continue;
      }
      b.append(String.format(Locale.ROOT, "  %-26s p50 %10.1f  p99 %10.1f  p99.9 %10.1f  p99.99 %10.1f  max %10.1f us%n",
          e.getKey(), h.getValueAtPercentile(50) / 1e3, h.getValueAtPercentile(99) / 1e3,
          h.getValueAtPercentile(99.9) / 1e3, h.getValueAtPercentile(99.99) / 1e3, h.getMaxValue() / 1e3));
    }
    return b.toString();
  }

  private static String quote(final String s) {
    final StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      final char ch = s.charAt(i);
      if (ch == '"' || ch == '\\') {
        b.append('\\').append(ch);
      } else if (ch < 0x20) {
        b.append(' ');
      } else {
        b.append(ch);
      }
    }
    return b.append('"').toString();
  }

  private static void deleteTree(final Path dir) {
    try (Stream<Path> files = Files.walk(dir)) {
      for (final Path f : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(f);
      }
    } catch (final IOException e) {
      // a temporary directory; the operating system clears it in time
    }
  }
}
