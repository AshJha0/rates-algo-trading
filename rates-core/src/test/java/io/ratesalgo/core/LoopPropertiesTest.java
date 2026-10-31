package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.chaos.FaultInjector;
import io.ratesalgo.core.chaos.FaultInjector.FaultPlan;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;

/**
 * The two loop properties of the P2 gate: every message the faulty network delivers is processed
 * exactly once and in delivery order, and replaying any generated run reproduces its outbound hash.
 */
class LoopPropertiesTest {

  @Property(tries = 300)
  void everyDeliveredMessageIsProcessedExactlyOnceInDeliveryOrder(
      @ForAll final long seed,
      @ForAll @IntRange(min = 1, max = 400) final int messages,
      @ForAll @DoubleRange(min = 0.0, max = 0.2) final double drop,
      @ForAll @DoubleRange(min = 0.0, max = 0.2) final double duplicate,
      @ForAll @DoubleRange(min = 0.0, max = 0.2) final double delay,
      @ForAll @DoubleRange(min = 0.0, max = 0.2) final double reorder) {
    final ManyToOneRingBuffer ring = RingBufferContract.newInbound(RingBufferContract.MIN_CAPACITY);
    final InboundPublisher publisher = new InboundPublisher(ring);
    final List<Long> delivered = new ArrayList<>();
    final List<Long> processed = new ArrayList<>();
    final FaultInjector faults =
        new FaultInjector(new FaultPlan(drop, duplicate, delay, reorder, 5, seed), publisher::offer)
            .observer((b, o, l) -> delivered.add(Msgs.rfqId(b, o)));
    final EventLoop loop = EventLoop.builder(new EventClock(new ManualClock())).inbound(ring).readLimit(16).build();
    loop.register(QuoteRequestDecoder.TEMPLATE_ID, (t, b, o, l) -> processed.add(Msgs.rfqId(b, o)));

    final SplittableRandom pace = new SplittableRandom(seed ^ 0x5DEECE66DL);
    final UnsafeBuffer msg = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
    for (int i = 1; i <= messages; i++) {
      faults.offer(msg, 0, Msgs.quoteRequest(msg, 0, i));
      if (pace.nextInt(8) == 0) {
        loop.doWork();
      }
    }
    faults.flush();
    // a read that consumes only the wrap padding returns 0, so drain by ring size
    while (loop.doWork() > 0 || ring.size() > 0) {
      // drain
    }
    assertEquals(delivered, processed);
    assertEquals(faults.deliveredCount(), loop.counters().get(LoopCounters.INBOUND));
    assertEquals(faults.refusedCount(), publisher.rejectedCount());
  }

  @Property(tries = 60)
  void replayOfAnyRunReproducesTheOutboundHash(
      @ForAll final long seed,
      @ForAll @IntRange(min = 1, max = 300) final int messages,
      @ForAll @IntRange(min = 1024, max = 8192) final int segment,
      @ForAll @DoubleRange(min = 0.0, max = 0.15) final double faultRate)
      throws IOException {
    final Path dir = Files.createTempDirectory("loopprop");
    try {
      final QuoteApp live = new QuoteApp(true);
      final TestRig rig = new TestRig(dir, live, segment, RingBufferContract.MIN_CAPACITY);
      final FaultInjector faults =
          new FaultInjector(
              new FaultPlan(faultRate, faultRate, faultRate, faultRate, 4, seed), rig.publisher::offer);
      final SplittableRandom r = new SplittableRandom(seed);
      for (int i = 1; i <= messages; i++) {
        faults.offer(rig.scratch, 0, Msgs.quoteRequest(rig.scratch, 0, i));
        switch (r.nextInt(10)) {
          case 0, 1, 2 -> rig.cycle();
          case 3 -> {
            rig.advance(r.nextLong(1, 4_000_000L));
            rig.cycle();
          }
          case 4 -> rig.drain(0);
          case 5 -> {
            if (r.nextInt(20) == 0) {
              faults.gatewayStatus(0, r.nextBoolean(), rig.clock.epochNanos());
            }
          }
          default -> {
            // let messages queue
          }
        }
        rig.drain(1);
      }
      faults.flush();
      rig.advance(10_000_000L);
      while (rig.cycle() > 0 || rig.inbound.size() > 0) {
        rig.drain(1);
      }
      rig.close();

      final ReplayResult result = rig.replay(new QuoteApp(true));
      assertTrue(result.matches(), result.toString());
      assertEquals(rig.loop.counters().get(LoopCounters.OUTBOUND), result.outboundProduced());
    } finally {
      try (Stream<Path> files = Files.list(dir)) {
        for (final Path p : files.toList()) {
          Files.delete(p);
        }
      }
      Files.delete(dir);
    }
  }
}
