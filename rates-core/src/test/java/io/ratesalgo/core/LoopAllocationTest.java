package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.journal.JournalWriter;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The P2 allocation gate: after warm-up, one million QuoteRequests go through a live loop on its
 * own thread (inbound ring, inbound and outbound journals, a handler that decodes and publishes a
 * Quote, a timer every 1024 messages) and the loop thread's allocated-bytes counter must not move.
 * Warm-up is one million messages, then rounds of 100,000 until a round allocates nothing.
 *
 * <p>When this fails, rerun with {@code -XX:StartFlightRecording=settings=profile} and look at the
 * jdk.ObjectAllocationSample events for the thread named "alloc-check-loop".
 */
class LoopAllocationTest {

  private static final int WARM_UP = 1_000_000;
  private static final int MEASURED = 1_000_000;
  private static final int SETTLE_ROUND = 100_000;
  private static final int MAX_SETTLE_ROUNDS = 20;

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

  @Test
  void loopThreadAllocatesNothingOverOneMillionMessages() throws Exception {
    final EventClock clock = new EventClock(new AnchoredClock(SystemClock.INSTANCE));
    final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
    final OneToOneRingBuffer outbound = RingBufferContract.newOutbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
    // 96-byte inbound frames and 120-byte outbound frames, plus clock ticks
    try (JournalWriter in = JournalWriter.create(dir.resolve("in.raj"), 512 << 20, clock);
        JournalWriter out = JournalWriter.create(dir.resolve("out.raj"), 512 << 20, clock)) {
      final EventLoop loop =
          EventLoop.builder(clock)
              .inbound(inbound)
              .outbound(outbound)
              .inboundJournal(in::append)
              .outboundJournal(out::append)
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
      final long loopThread = runner.threadId();

      final InboundPublisher publisher = new InboundPublisher(inbound);
      publish(publisher, 1, WARM_UP);
      awaitInbound(loop, WARM_UP);
      // keep warming until a round allocates nothing: the JIT may still recompile after the
      // first million (seen as a one-off 72 to 296 bytes on 2026-10-31, never repeated)
      int sent = WARM_UP;
      int rounds = 0;
      long roundBytes;
      do {
        final long start = threads.getThreadAllocatedBytes(loopThread);
        publish(publisher, sent + 1, sent + SETTLE_ROUND);
        sent += SETTLE_ROUND;
        awaitInbound(loop, sent);
        roundBytes = threads.getThreadAllocatedBytes(loopThread) - start;
        rounds++;
      } while (roundBytes != 0 && rounds < MAX_SETTLE_ROUNDS);
      final long before = threads.getThreadAllocatedBytes(loopThread);
      publish(publisher, sent + 1, sent + MEASURED);
      sent += MEASURED;
      awaitInbound(loop, sent);
      final long after = threads.getThreadAllocatedBytes(loopThread);

      runner.close();
      draining.set(false);
      drainer.join();
      assertNull(runner.failure());
      assertEquals(sent, loop.counters().get(LoopCounters.OUTBOUND));
      assertEquals(0L, after - before, "bytes allocated on the loop thread over " + MEASURED + " messages");
    }
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
