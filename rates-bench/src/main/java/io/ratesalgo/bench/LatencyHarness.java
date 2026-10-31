package io.ratesalgo.bench;

import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.core.AnchoredClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.IdleStrategies;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.JournalSink;
import io.ratesalgo.core.LoopCounters;
import io.ratesalgo.core.LoopRunner;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.journal.SegmentedJournal;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.Side;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;

/**
 * The fixed-rate latency harness for the event core (docs/BENCHMARKS.md).
 *
 * <p>Three threads besides the caller: a generator that publishes QuoteRequests into the loop's
 * inbound ring on a fixed schedule (it never waits for a response, and the schedule is what the
 * scheduled-to-encode interval is measured from), the loop thread running {@link BenchQuoter}, and
 * a gateway thread that drains Quotes from the outbound ring and hands them to the {@link
 * TickToWireProbe}. The caller reads the loop thread's allocated bytes at the end of warm-up and at
 * the end, then writes a JSON report and Markdown rows.
 *
 * <p>Usage: {@code mvn -pl rates-bench exec:exec@latency} with {@code -Dlatency.rate},
 * {@code -Dlatency.warmup}, {@code -Dlatency.measure} and the other properties in the module pom,
 * or {@code java ... io.ratesalgo.bench.LatencyHarness --rate=50000 --warmup-seconds=60
 * --measure-seconds=300 --idle=busy-spin --journal=true --out=target/latency}.
 */
public final class LatencyHarness {

  private static final int SCHEDULE_SLOTS = 1 << 20;
  private static final long DRAIN_TIMEOUT_NANOS = 60_000_000_000L;

  private LatencyHarness() {}

  /**
   * What a run produced.
   *
   * @param jsonFile the JSON report
   * @param markdownFile the Markdown rows
   * @param json the JSON text
   * @param decodeToEncode T3 minus T2 histogram, nanoseconds
   * @param scheduledToEncode T3 minus T0 histogram, nanoseconds
   * @param allocationDelta bytes allocated on the loop thread from end of warm-up to end of run
   * @param generated messages the generator scheduled
   * @param generatorDropped messages refused by a full inbound ring
   */
  public record Result(
      Path jsonFile,
      Path markdownFile,
      String json,
      Histogram decodeToEncode,
      Histogram scheduledToEncode,
      long allocationDelta,
      long generated,
      long generatorDropped) {}

  public static void main(final String[] args) throws IOException, InterruptedException {
    final Result r = run(HarnessConfig.parse(args), TickToWireProbe.NONE);
    System.out.println(Files.readString(r.markdownFile(), StandardCharsets.UTF_8));
    System.out.println("report: " + r.jsonFile());
  }

  /** Runs the harness once. */
  public static Result run(final HarnessConfig cfg, final TickToWireProbe probe)
      throws IOException, InterruptedException {
    final MachineInfo machine = MachineInfo.detect();
    final Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    final AnchoredClock live = new AnchoredClock(SystemClock.INSTANCE);
    final EventClock clock = new EventClock(live);
    final Path journalDir = cfg.journal() ? Files.createTempDirectory("rates-bench-journal") : null;
    final SegmentedJournal in =
        cfg.journal() ? SegmentedJournal.create(journalDir, "bench-in", cfg.segmentBytes(), clock) : null;
    final SegmentedJournal out =
        cfg.journal() ? SegmentedJournal.create(journalDir, "bench-out", cfg.segmentBytes(), clock) : null;
    final ManyToOneRingBuffer inbound =
        RingBufferContract.newInbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
    final OneToOneRingBuffer outbound = RingBufferContract.newOutbound(1 << 20);
    final EventLoop loop =
        EventLoop.builder(clock)
            .inbound(inbound)
            .outbound(outbound)
            .inboundJournal(in == null ? JournalSink.NONE : in::append)
            .outboundJournal(out == null ? JournalSink.NONE : out::append)
            .build();
    final long[] scheduled = new long[SCHEDULE_SLOTS];
    final BenchQuoter quoter =
        new BenchQuoter(loop, SystemClock.INSTANCE, scheduled, cfg.warmupMessages(), cfg.intervalNanos());
    loop.register(QuoteRequestDecoder.TEMPLATE_ID, quoter);

    final AtomicBoolean draining = new AtomicBoolean(true);
    final Thread gateway =
        new Thread(
            () -> {
              while (draining.get() || outbound.size() > 0) {
                final int n =
                    outbound.read(
                        (t, b, i, l) -> probe.onOutbound(b, i, l, SystemClock.INSTANCE.nanoTime()), 256);
                if (n == 0) {
                  Thread.onSpinWait();
                }
              }
            },
            "bench-gateway-out");
    gateway.setDaemon(true);

    final LoopRunner runner =
        LoopRunner.builder(loop)
            .name("bench-core-loop")
            .idleStrategy(IdleStrategies.of(cfg.idle()))
            .cpu(cfg.cpu())
            .daemon(true)
            .startOfDay(l -> live.reanchor())
            .build();
    final Generator generator = new Generator(cfg, new InboundPublisher(inbound), scheduled);
    final Thread generatorThread = new Thread(generator, "bench-generator");
    generatorThread.setDaemon(true);

    final com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long allocationDelta;
    try {
      gateway.start();
      runner.start();
      generatorThread.start();
      final long loopThread = runner.threadId();
      while (generator.progress.get() < cfg.warmupMessages() && generatorThread.isAlive()) {
        Thread.sleep(1);
      }
      final long before = threads.getThreadAllocatedBytes(loopThread);
      generatorThread.join();
      final long expected = generator.total - generator.dropped;
      final long deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS;
      while (loop.counters().get(LoopCounters.INBOUND) < expected) {
        if (System.nanoTime() > deadline || !runner.isRunning()) {
          throw new IllegalStateException(
              "loop processed "
                  + loop.counters().get(LoopCounters.INBOUND)
                  + " of "
                  + expected
                  + (runner.failure() == null ? "" : ": " + runner.failure()));
        }
        Thread.sleep(1);
      }
      allocationDelta = threads.getThreadAllocatedBytes(loopThread) - before;
    } finally {
      runner.close();
      draining.set(false);
      gateway.join();
      if (in != null) {
        in.close();
        out.close();
        deleteTree(journalDir);
      }
    }

    final String date = DateTimeFormatter.ISO_LOCAL_DATE.format(started.atZone(ZoneOffset.UTC));
    final String stamp =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).format(started.atZone(ZoneOffset.UTC));
    Files.createDirectories(cfg.outDir());
    final Path jsonFile = cfg.outDir().resolve("latency-" + stamp + ".json");
    final Path mdFile = cfg.outDir().resolve("latency-" + stamp + ".md");
    final String json =
        json(cfg, machine, started, runner.isPinned(), generator, loop, quoter, allocationDelta);
    Files.writeString(jsonFile, json, StandardCharsets.UTF_8);
    final String reportName = String.valueOf(jsonFile.getFileName());
    final String md =
        LatencyReport.markdownRow(
                date, machine, "core decode-to-encode", cfg.rate(), quoter.decodeToEncode(), allocationDelta, reportName)
            + "\n"
            + LatencyReport.markdownRow(
                date, machine, "scheduled-to-encode", cfg.rate(), quoter.scheduledToEncode(), allocationDelta, reportName)
            + "\n";
    Files.writeString(mdFile, md, StandardCharsets.UTF_8);
    return new Result(
        jsonFile,
        mdFile,
        json,
        quoter.decodeToEncode(),
        quoter.scheduledToEncode(),
        allocationDelta,
        generator.total,
        generator.dropped);
  }

  private static String json(
      final HarnessConfig cfg,
      final MachineInfo m,
      final Instant started,
      final boolean pinned,
      final Generator g,
      final EventLoop loop,
      final BenchQuoter q,
      final long allocationDelta) {
    final StringBuilder b = new StringBuilder(8192);
    b.append("{\n  \"schema\": ").append(LatencyReport.quote(LatencyReport.SCHEMA));
    b.append(",\n  \"started\": ").append(LatencyReport.quote(started.toString()));
    b.append(",\n  \"label\": ").append(LatencyReport.quote(cfg.label()));
    b.append(",\n  \"commit\": ").append(LatencyReport.quote(m.commit()));
    b.append(",\n  \"machine\": {\"model\": ").append(LatencyReport.quote(m.model()));
    b.append(", \"cpu\": ").append(LatencyReport.quote(m.cpu()));
    b.append(", \"logicalCpus\": ").append(m.logicalCpus());
    b.append(", \"memoryBytes\": ").append(m.memoryBytes());
    b.append(", \"os\": ").append(LatencyReport.quote(m.os()));
    b.append(", \"arch\": ").append(LatencyReport.quote(m.arch())).append('}');
    b.append(",\n  \"jvm\": {\"version\": ").append(LatencyReport.quote(m.jvm())).append(", \"flags\": [");
    for (int i = 0; i < m.jvmFlags().size(); i++) {
      b.append(i == 0 ? "" : ", ").append(LatencyReport.quote(m.jvmFlags().get(i)));
    }
    b.append("]}");
    b.append(",\n  \"config\": {\"rate\": ").append(cfg.rate());
    b.append(", \"intervalNanos\": ").append(cfg.intervalNanos());
    b.append(", \"warmupSeconds\": ").append(cfg.warmupSeconds());
    b.append(", \"measureSeconds\": ").append(cfg.measureSeconds());
    b.append(", \"idle\": ").append(LatencyReport.quote(cfg.idle()));
    b.append(", \"journal\": ").append(cfg.journal());
    b.append(", \"segmentBytes\": ").append(cfg.segmentBytes());
    b.append(", \"cpu\": ").append(cfg.cpu());
    b.append(", \"pinned\": ").append(pinned).append('}');
    b.append(",\n  \"warmupPolicy\": ")
        .append(
            LatencyReport.quote(
                "first "
                    + cfg.warmupMessages()
                    + " messages ("
                    + cfg.warmupSeconds()
                    + " s at the fixed rate) run through the same path and are not recorded"));
    b.append(",\n  \"counts\": {\"generated\": ").append(g.total);
    b.append(", \"generatorDropped\": ").append(g.dropped);
    for (int id = 0; id < LoopCounters.COUNT; id++) {
      b.append(", \"").append(LoopCounters.name(id)).append("\": ").append(loop.counters().get(id));
    }
    b.append('}');
    b.append(",\n  \"allocationDeltaBytes\": ").append(allocationDelta);
    b.append(",\n  \"generatorBehindSchedule\": ")
        .append(g.lateness.getTotalCount() > 0 && g.lateness.getValueAtPercentile(99.0) > cfg.intervalNanos());
    b.append(",\n  \"decodeToEncode\": ").append(LatencyReport.intervalJson(q.decodeToEncode()));
    b.append(",\n  \"scheduledToEncode\": ").append(LatencyReport.intervalJson(q.scheduledToEncode()));
    b.append(",\n  \"generatorLateness\": ").append(LatencyReport.intervalJson(g.lateness));
    b.append(",\n  \"tickToWire\": null");
    b.append(",\n  \"notes\": ")
        .append(
            LatencyReport.quote(
                "in-process: generator, loop and gateway threads share one JVM and one clock;"
                    + " decodeToEncode is T3-T2 recorded with recordValueWithExpectedInterval at the"
                    + " generator interval; scheduledToEncode is T3-T0 from the scheduled send time;"
                    + " tickToWire is left for the FIX codec (TickToWireProbe)"));
    b.append("\n}\n");
    return b.toString();
  }

  private static void deleteTree(final Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      for (final Path p : files.toList()) {
        Files.delete(p);
      }
    }
    Files.delete(dir);
  }

  /** Publishes QuoteRequests on a fixed schedule; never waits for the loop. */
  static final class Generator implements Runnable {

    final long total;
    final Histogram lateness = new Histogram(BenchQuoter.HIGHEST_NANOS, 3);
    final AtomicLong progress = new AtomicLong();
    long dropped;

    private final long interval;
    private final InboundPublisher publisher;
    private final long[] scheduled;
    private final int mask;
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();
    private final QuoteRequestEncoder request = new QuoteRequestEncoder();
    private static final int LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + QuoteRequestEncoder.BLOCK_LENGTH;

    Generator(final HarnessConfig cfg, final InboundPublisher publisher, final long[] scheduled) {
      this.total = cfg.warmupMessages() + cfg.measuredMessages();
      this.interval = cfg.intervalNanos();
      this.publisher = publisher;
      this.scheduled = scheduled;
      this.mask = scheduled.length - 1;
    }

    @Override
    public void run() {
      final long start = SystemClock.INSTANCE.nanoTime() + 10_000_000L;
      for (long i = 0; i < total; i++) {
        final long t0 = start + i * interval;
        long now;
        while ((now = SystemClock.INSTANCE.nanoTime()) < t0) {
          Thread.onSpinWait();
        }
        lateness.recordValue(Math.min(now - t0, BenchQuoter.HIGHEST_NANOS));
        scheduled[(int) (i & mask)] = t0;
        final int index = publisher.tryClaim(QuoteRequestDecoder.TEMPLATE_ID, LENGTH);
        if (index < 0) {
          dropped++;
        } else {
          request
              .wrapAndApplyHeader(publisher.buffer(), index, header)
              .venueId(7)
              .rfqId(i)
              .clientId((int) (i % 97))
              .instrumentId(1000 + (int) (i % 16))
              .side((i & 1) == 0 ? Side.Buy : Side.Sell)
              .quantity(10_000_000_000L)
              .currency("USD")
              .dealerCount((short) 4)
              .receivedNanos(t0)
              .expiryNanos(t0 + 30_000_000_000L);
          publisher.commit(index);
        }
        progress.lazySet(i + 1);
      }
    }
  }
}
