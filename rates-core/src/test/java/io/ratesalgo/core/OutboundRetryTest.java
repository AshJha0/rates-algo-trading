package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.junit.jupiter.api.Test;

/**
 * Must-deliver outbound messages and a full gateway ring (review finding L2): a dropped cancel used
 * to be counted and forgotten, leaving the quote or order at the venue. With {@link
 * EventLoop.Builder#retryOnDrop} the loop holds it and delivers it, in order, once the gateway has
 * room. Also the runner's bounded close and the duty-cycle heartbeat the supervisor reads.
 */
class OutboundRetryTest {

  private static final int TEMPLATE = QuoteRequestEncoder.TEMPLATE_ID;

  private final ManualClock source = new ManualClock();
  private final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(RingBufferContract.MIN_CAPACITY * 4);
  private final OneToOneRingBuffer gateway = RingBufferContract.newOutbound(RingBufferContract.MIN_CAPACITY);
  private final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
  private final List<Long> delivered = new ArrayList<>();

  /** A loop that echoes every QuoteRequest to gateway 0. */
  private EventLoop echo(final EventLoop.Builder builder) {
    final EventLoop[] holder = new EventLoop[1];
    final EventLoop loop = builder.inbound(inbound).outbound(gateway).build();
    holder[0] = loop;
    loop.register(TEMPLATE, (templateId, buffer, offset, length) -> holder[0].publish(0, buffer, offset, length));
    return loop;
  }

  private void send(final EventLoop loop, final long rfqId) {
    assertTrue(inbound.write(TEMPLATE, scratch, 0, Msgs.quoteRequest(scratch, 0, rfqId)));
    loop.doWork();
  }

  private void drainGateway() {
    // one read stops at the ring's wrap point, so read until the ring is empty
    while (gateway.size() > 0) {
      gateway.read((type, buffer, index, length) -> delivered.add(Msgs.rfqId(buffer, index)));
    }
  }

  /** Messages the gateway ring did not take: dropped, held for retry, or lost from the retry buffer. */
  private static long refused(final EventLoop loop) {
    final LoopCounters c = loop.counters();
    final long held = c.get(LoopCounters.OUTBOUND_RETRY_QUEUED) + c.get(LoopCounters.OUTBOUND_RETRY_LOST);
    return Math.max(held, c.get(LoopCounters.OUTBOUND_DROPPED));
  }

  /** Publishes until the gateway ring has refused {@code extra} messages; returns how many it took. */
  private int fillAndOverflow(final EventLoop loop, final int extra) {
    int id = 0;
    while (refused(loop) < extra) {
      send(loop, ++id);
      assertTrue(id < 100_000, "the gateway ring never filled");
    }
    return id;
  }

  @Test
  void aDroppedMustDeliverMessageIsHeldAndDeliveredInOrderWhenTheRingHasRoom() {
    final EventLoop loop = echo(EventLoop.builder(new EventClock(source)).retryOnDrop(TEMPLATE));
    final int sent = fillAndOverflow(loop, 3);
    assertEquals(3, loop.counters().get(LoopCounters.OUTBOUND_RETRY_QUEUED));
    assertTrue(loop.hasHeldOutbound());
    // one more while older ones wait: it queues behind them without touching the ring
    send(loop, sent + 1);
    assertEquals(1, loop.counters().get(LoopCounters.OUTBOUND_DROPPED), "later ones queue behind the first drop");
    assertEquals(4, loop.counters().get(LoopCounters.OUTBOUND_RETRY_QUEUED));

    loop.doWork();
    assertEquals(0, loop.counters().get(LoopCounters.OUTBOUND_RETRIED), "ring still full: nothing delivered yet");

    drainGateway();
    assertEquals(sent - 3, delivered.size());
    assertEquals(4, loop.doWork(), "the retries count as work");
    assertFalse(loop.hasHeldOutbound());
    drainGateway();
    assertEquals(sent + 1, delivered.size(), "every message reached the gateway");
    for (int i = 0; i < delivered.size(); i++) {
      assertEquals(i + 1L, delivered.get(i), "order kept at " + i);
    }
    assertEquals(4, loop.counters().get(LoopCounters.OUTBOUND_RETRIED));
    assertEquals(0, loop.counters().get(LoopCounters.OUTBOUND_RETRY_LOST));
  }

  @Test
  void withoutTheRetryADroppedMessageIsLostAsBefore() {
    final EventLoop loop = echo(EventLoop.builder(new EventClock(source)));
    final int sent = fillAndOverflow(loop, 3);
    drainGateway();
    loop.doWork();
    drainGateway();
    assertEquals(sent - 3, delivered.size());
    assertEquals(0, loop.counters().get(LoopCounters.OUTBOUND_RETRY_QUEUED));
    assertFalse(loop.hasHeldOutbound());
  }

  @Test
  void aFullRetryBufferCountsTheLoss() {
    final EventLoop loop =
        echo(EventLoop.builder(new EventClock(source)).retryOnDrop(TEMPLATE).retryCapacity(2 * 80));
    fillAndOverflow(loop, 4);
    assertEquals(2, loop.counters().get(LoopCounters.OUTBOUND_RETRY_QUEUED), "two 80-byte records fit");
    assertEquals(2, loop.counters().get(LoopCounters.OUTBOUND_RETRY_LOST));
    assertThrows(IllegalArgumentException.class, () -> EventLoop.builder(new EventClock(source)).retryOnDrop(0));
    assertThrows(IllegalArgumentException.class, () -> EventLoop.builder(new EventClock(source)).retryCapacity(8));
  }

  @Test
  void everyDutyCycleMovesTheHeartbeatCounter() {
    final EventLoop loop = echo(EventLoop.builder(new EventClock(source)));
    final long before = loop.counters().get(LoopCounters.DUTY_CYCLES);
    loop.doWork();
    loop.doWork();
    assertEquals(before + 2, loop.counters().get(LoopCounters.DUTY_CYCLES));
  }

  @Test
  void closeDoesNotWaitForeverForALoopStuckInADutyCycle() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch never = new CountDownLatch(1);
    final EventLoop loop = echo(EventLoop.builder(new EventClock(source)));
    loop.addAgent(
        () -> {
          entered.countDown();
          try {
            never.await();
          } catch (final InterruptedException e) {
            throw new IllegalStateException("interrupted", e);
          }
          return 0;
        });
    final LoopRunner runner =
        LoopRunner.builder(loop).name("stuck-loop").daemon(true).closeTimeoutMillis(50).build().start();
    assertTrue(entered.await(30, TimeUnit.SECONDS));
    // before the bound this join never returned
    assertTimeoutPreemptively(Duration.ofSeconds(30), runner::close);
    assertTrue(runner.awaitTermination(30, TimeUnit.SECONDS), "the interrupt ended the stuck cycle");
    assertFalse(runner.isAlive());
    assertTrue(runner.failure() instanceof IllegalStateException);
    assertThrows(IllegalArgumentException.class, () -> LoopRunner.builder(loop).closeTimeoutMillis(0));
  }
}
