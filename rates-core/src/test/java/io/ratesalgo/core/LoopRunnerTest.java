package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.junit.jupiter.api.Test;

class LoopRunnerTest {

  private final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(1 << 16);
  private final InboundPublisher publisher = new InboundPublisher(inbound);
  private final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(256));

  @Test
  void runsHooksAndCyclesOnANamedThreadUntilClosed() throws Exception {
    final EventClock clock = new EventClock(new AnchoredClock(SystemClock.INSTANCE));
    final List<String> events = new CopyOnWriteArrayList<>();
    final EventLoop loop = EventLoop.builder(clock).inbound(inbound).build();
    loop.register(
        QuoteRequestDecoder.TEMPLATE_ID,
        (t, b, o, l) -> events.add("msg on " + Thread.currentThread().getName()));
    final LoopRunner runner =
        LoopRunner.builder(loop)
            .name("core-usd-swaps")
            .idleStrategy(new YieldingIdleStrategy())
            .daemon(true)
            .startOfDay(l -> events.add("sod"))
            .endOfDay(l -> events.add("eod"))
            .build()
            .start();
    publisher.offer(scratch, 0, Msgs.quoteRequest(scratch, 0, 1));
    while (loop.counters().get(LoopCounters.INBOUND) < 1) {
      Thread.onSpinWait();
    }
    assertTrue(runner.isRunning());
    assertFalse(runner.isPinned());
    assertEquals("core-usd-swaps", runner.threadName());
    runner.close();
    assertFalse(runner.isRunning());
    assertNull(runner.failure());
    assertEquals(List.of("sod", "msg on core-usd-swaps", "eod"), events);
  }

  @Test
  void aThrowingHandlerJournalsALoopErrorAndStopsWithoutEndOfDay() throws Exception {
    final EventClock clock = new EventClock(new ManualClock());
    final List<Integer> journaled = new CopyOnWriteArrayList<>();
    final AtomicBoolean eod = new AtomicBoolean();
    final EventLoop loop =
        EventLoop.builder(clock)
            .inbound(inbound)
            .inboundJournal(
                (t, v, b, o, l) -> {
                  journaled.add(t);
                  return journaled.size();
                })
            .build();
    loop.register(
        QuoteRequestDecoder.TEMPLATE_ID,
        (t, b, o, l) -> {
          throw new IllegalStateException("bad state");
        });
    final LoopRunner runner =
        LoopRunner.builder(loop)
            .idleStrategy(new BusySpinIdleStrategy())
            .daemon(true)
            .endOfDay(l -> eod.set(true))
            .build()
            .start();
    publisher.offer(scratch, 0, Msgs.quoteRequest(scratch, 0, 1));
    assertTrue(runner.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals("bad state", runner.failure().getMessage());
    assertEquals(List.of(QuoteRequestDecoder.TEMPLATE_ID, ControlMessages.LOOP_ERROR_TEMPLATE_ID), journaled);
    assertFalse(eod.get());
    assertFalse(runner.isRunning());
    runner.close();
  }

  @Test
  void aFailureToJournalTheErrorIsAttachedToTheFailure() throws Exception {
    final EventLoop loop =
        EventLoop.builder(new EventClock(new ManualClock()))
            .inboundJournal(
                (t, v, b, o, l) -> {
                  throw new IllegalStateException("journal gone");
                })
            .build();
    final LoopRunner runner =
        LoopRunner.builder(loop)
            .daemon(true)
            .startOfDay(
                l -> {
                  throw new IllegalArgumentException("bad config");
                })
            .build()
            .start();
    assertTrue(runner.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals("bad config", runner.failure().getMessage());
    assertEquals("journal gone", runner.failure().getSuppressed()[0].getMessage());
  }

  @Test
  void idleStrategiesByName() {
    assertTrue(IdleStrategies.of("busy-spin") instanceof BusySpinIdleStrategy);
    assertTrue(IdleStrategies.of("YIELD") instanceof YieldingIdleStrategy);
    assertTrue(IdleStrategies.of("backoff") instanceof BackoffIdleStrategy);
    assertThrows(IllegalArgumentException.class, () -> IdleStrategies.of("sleep"));
  }

  @Test
  void pinningIsSkippedOffLinuxAndForNegativeCpus() throws Exception {
    assertFalse(CoreAffinity.pin(-1));
    assertFalse(CoreAffinity.pin(0, "Windows 11"));
    assertFalse(CoreAffinity.pin(-1, "Linux"));
    // on a Linux host this pins a throwaway thread; elsewhere the library may refuse, which is
    // reported as not pinned rather than thrown
    final boolean[] result = new boolean[1];
    final Thread t = new Thread(() -> result[0] = CoreAffinity.pin(0, "Linux"));
    t.start();
    t.join();
    assertTrue(result[0] || !System.getProperty("os.name", "").startsWith("Linux"));
  }

  @Test
  void anchoredClockMovesWallTimeWithMonotonicTimeAndNeverBackwards() {
    final ManualClock manual = new ManualClock(1_000_000L);
    final AnchoredClock anchored = new AnchoredClock(manual);
    manual.advance(500L);
    assertEquals(1_000_500L, anchored.epochNanos());
    assertEquals(manual.nanoTime(), anchored.nanoTime());
    anchored.reanchor();
    assertEquals(1_000_500L, anchored.epochNanos());
    final EventClock latched = new EventClock(anchored);
    manual.advance(10L);
    assertEquals(1_000_500L, latched.epochNanos());
    latched.latch();
    assertEquals(1_000_510L, latched.epochNanos());
    assertEquals(manual.nanoTime(), latched.nanoTime());
    assertSame(anchored, latched.source());
  }

  @Test
  void anchoredClockReanchorKeepsTimeWhenTheSourceWallClockStepsBack() {
    final long[] wall = {5_000L};
    final long[] mono = {100L};
    final io.ratesalgo.common.time.Clock stepping =
        new io.ratesalgo.common.time.Clock() {
          @Override
          public long nanoTime() {
            return mono[0];
          }

          @Override
          public long epochNanos() {
            return wall[0];
          }
        };
    final AnchoredClock anchored = new AnchoredClock(stepping);
    mono[0] = 200L;
    wall[0] = 4_000L; // the system clock stepped back
    anchored.reanchor();
    assertEquals(5_100L, anchored.epochNanos());
    wall[0] = 9_000L;
    anchored.reanchor();
    assertEquals(9_000L, anchored.epochNanos());
  }
}
