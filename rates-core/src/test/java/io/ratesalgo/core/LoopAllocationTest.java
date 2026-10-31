package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

/**
 * The P2 allocation gate: after warm-up, QuoteRequests go through a live loop on its own thread
 * (inbound ring, inbound and outbound journals, a handler that decodes and publishes a Quote, a
 * timer every 1024 messages) and the loop thread's allocated-bytes counter must not move. It runs
 * once per {@link JournalMode}, and each journal rolls to a new segment inside the measured
 * windows (DR-R2J-02).
 *
 * <p>The check is windowed (DR-INT-02). Warm-up is one million messages; then three consecutive
 * windows of one million messages are measured. The last window must allocate exactly nothing and
 * no window after the first may allocate more than {@link #LATER_WINDOW_BOUND} bytes. The first
 * window is reported but not bounded: a one-off allocation of a few hundred bytes just after
 * warm-up (a late JIT recompile, a rare path such as backpressure taken for the first time) is not
 * a per-message leak, and a per-message leak of even one byte shows as at least a megabyte in every
 * window. The message prints each window's bytes with its dropped-message and backpressure counts,
 * since a slow drainer moves the loop onto the backpressure path.
 *
 * <p>The counter is read from the test thread with the loop thread's id, so the read cannot
 * allocate on the loop thread; it is also read once before warm-up so that any first-call cost of
 * the bean falls outside the windows.
 *
 * <p>When this fails, rerun with {@code -XX:StartFlightRecording=settings=profile} and look at the
 * jdk.ObjectAllocationSample events for the thread named "alloc-check-loop".
 */
class LoopAllocationTest {

  private static final int WARM_UP = 1_000_000;
  private static final int WINDOW = 1_000_000;
  private static final int WINDOWS = 3;
  private static final long LATER_WINDOW_BOUND = 1024L;
  private static final int SEGMENT = 256 << 20;

  @TempDir Path dir;

  /** Decodes the request, encodes a Quote, publishes it; every 1024th request sets a timer. */
  private static final class TrivialQuoter implements MessageHandler {
    private final EventLoop loop;
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final QuoteRequestDecoder request = new QuoteRequestDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final QuoteEncoder quote = new QuoteEncoder();
    private final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final io.ratesalgo.common.time.TimerHandler onTimer = (id, now) -> {};

    TrivialQuoter(final EventLoop loop) {
      this.loop = loop;
    }

    @Override
    public void onMessage(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
      request.wrapAndApplyHeader(buffer, offset, headerDecoder);
      final long rfqId = request.rfqId();
      quote.wrapAndApplyHeader(out, 0, headerEncoder).venueId(request.venueId()).rfqId(rfqId).quoteId(rfqId);
      quote.bidPrice().mantissa(99_000_000_000L);
      quote.askPrice().mantissa(101_000_000_000L);
      quote.sentNanos(loop.clock().epochNanos());
      loop.publish(0, out, 0, quote.limit());
      if ((rfqId & 1023) == 0) {
        loop.timers().schedule(loop.clock().nanoTime(), onTimer);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(JournalMode.class)
  void loopThreadAllocatesNothingOverOneMillionMessages(final JournalMode mode) throws Exception {
    final EventClock clock = new EventClock(new AnchoredClock(SystemClock.INSTANCE));
    final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
    final OneToOneRingBuffer outbound = RingBufferContract.newOutbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
    // 4 million messages: 96-byte inbound and 120-byte outbound frames, plus clock ticks and, on a
    // 2-CPU runner where the drainer falls behind, a 64-byte Backpressure frame per dropped Quote.
    // That is 0.4 to 0.6 GB per journal, so 256 MiB segments roll at least once in each journal
    // during the measured windows: the roll is inside the gate (DR-R2J-02).
    try (LoopJournals journals = LoopJournals.create(dir, "in", "out", SEGMENT, clock, mode)) {
      final EventLoop loop =
          EventLoop.builder(clock)
              .inbound(inbound)
              .outbound(outbound)
              .journals(journals)
              .build();
      loop.register(QuoteRequestDecoder.TEMPLATE_ID, new TrivialQuoter(loop));

      final AtomicBoolean draining = new AtomicBoolean(true);
      final Thread drainer =
          new Thread(
              () -> {
                while (draining.get()) {
                  if (outbound.read((t, b, i, l) -> {}, 1024) == 0) {
                    Thread.onSpinWait();
                  }
                }
              },
              "alloc-check-drain");
      drainer.setDaemon(true);
      drainer.start();

      final LoopRunner runner =
          LoopRunner.builder(loop)
              .name("alloc-check-loop")
              .idleStrategy(new BusySpinIdleStrategy())
              .daemon(true)
              .build()
              .start();
      final com.sun.management.ThreadMXBean threads =
          (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      assertTrue(threads.isThreadAllocatedMemoryEnabled(), "thread allocation accounting is off");
      final long loopThread = runner.threadId();
      // first call outside the windows: some JDKs allocate on the first use of the bean
      threads.getThreadAllocatedBytes(loopThread);

      final InboundPublisher publisher = new InboundPublisher(inbound);
      publish(publisher, 1, WARM_UP);
      awaitInbound(loop, WARM_UP);

      final long[] bytes = new long[WINDOWS];
      final long[] dropped = new long[WINDOWS];
      final long[] backpressure = new long[WINDOWS];
      int sent = WARM_UP;
      for (int w = 0; w < WINDOWS; w++) {
        final long droppedBefore = loop.counters().get(LoopCounters.OUTBOUND_DROPPED);
        final long backpressureBefore = loop.counters().get(LoopCounters.BACKPRESSURE_EVENTS);
        final long before = threads.getThreadAllocatedBytes(loopThread);
        publish(publisher, sent + 1, sent + WINDOW);
        sent += WINDOW;
        awaitInbound(loop, sent);
        bytes[w] = threads.getThreadAllocatedBytes(loopThread) - before;
        dropped[w] = loop.counters().get(LoopCounters.OUTBOUND_DROPPED) - droppedBefore;
        backpressure[w] = loop.counters().get(LoopCounters.BACKPRESSURE_EVENTS) - backpressureBefore;
      }

      runner.close();
      draining.set(false);
      drainer.join();
      assertNull(runner.failure());
      assertEquals(sent, loop.counters().get(LoopCounters.OUTBOUND));

      final String report =
          mode + " journals, " + journals.rollWaits() + " rolls waited, " + journals.producerWaits()
              + " ring waits; " + report(bytes, dropped, backpressure);
      for (int w = 1; w < WINDOWS; w++) {
        assertTrue(bytes[w] <= LATER_WINDOW_BOUND, "a window after the first exceeded " + LATER_WINDOW_BOUND + " bytes: " + report);
      }
      assertEquals(0L, bytes[WINDOWS - 1], "the last window allocated on the loop thread: " + report);
    }
  }

  private static String report(final long[] bytes, final long[] dropped, final long[] backpressure) {
    final StringBuilder sb = new StringBuilder("bytes allocated on the loop thread per window of ").append(WINDOW).append(" messages:");
    for (int w = 0; w < bytes.length; w++) {
      sb.append(" window ").append(w + 1).append('=').append(bytes[w])
          .append(" (outbound dropped ").append(dropped[w])
          .append(", backpressure events ").append(backpressure[w]).append(')');
    }
    return sb.toString();
  }

  private static void publish(final InboundPublisher publisher, final int from, final int to) {
    for (int i = from; i <= to; i++) {
      int index;
      while ((index = publisher.tryClaim(QuoteRequestDecoder.TEMPLATE_ID, Msgs.QUOTE_REQUEST_LENGTH)) < 0) {
        Thread.onSpinWait();
      }
      Msgs.quoteRequest(publisher.buffer(), index, i);
      publisher.commit(index);
    }
  }

  private static void awaitInbound(final EventLoop loop, final long count) throws InterruptedException {
    final long deadline = System.currentTimeMillis() + 120_000L;
    while (loop.counters().get(LoopCounters.INBOUND) < count) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("loop processed " + loop.counters().get(LoopCounters.INBOUND) + " of " + count);
      }
      Thread.sleep(1);
    }
  }
}
