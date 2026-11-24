package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.chaos.FaultInjector;
import io.ratesalgo.core.chaos.FaultInjector.FaultPlan;
import io.ratesalgo.messages.sbe.HeartbeatDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Boundaries and less-travelled paths of the event loop and its parts: control-message layout byte
 * by byte, the contract's inclusive length limits, when backpressure is raised and with what, the
 * builders' limits, the publisher's abort, the fault injector's delay, a replay that falls short and
 * the runner's thread handling.
 */
class CoreEdgesTest {

  private static final int REQUEST = QuoteRequestDecoder.TEMPLATE_ID;
  private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

  @TempDir Path dir;

  private final ManualClock source = new ManualClock();
  private final EventClock clock = new EventClock(source);
  private final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(2048));
  private final List<long[]> inJournal = new ArrayList<>();

  // ---------------------------------------------------------------- control messages

  @Test
  void controlMessagesHaveTheirDocumentedLayoutAndZeroPadding() {
    final UnsafeBuffer b = new UnsafeBuffer(new byte[512]);
    b.setMemory(0, 512, (byte) 0xFF);
    assertEquals(32, ControlMessages.encodeBackpressure(b, 0, 11L, 22L, 3, 0xFEDC));
    assertEquals(ControlMessages.BACKPRESSURE_BLOCK_LENGTH, b.getShort(0, LE));
    assertEquals((short) ControlMessages.BACKPRESSURE_TEMPLATE_ID, b.getShort(2, LE));
    assertEquals(ControlMessages.SCHEMA_ID, b.getShort(4, LE));
    assertEquals(ControlMessages.SCHEMA_VERSION, b.getShort(6, LE));
    assertEquals(11L, ControlMessages.timestampNanos(b, 0));
    assertEquals(22L, ControlMessages.backpressureDropped(b, 0));
    assertEquals(3, ControlMessages.backpressureGateway(b, 0));
    assertEquals(0xFEDC, ControlMessages.backpressureFirstTemplate(b, 0));
    assertEquals(0, b.getShort(8 + 22, LE), "padding");

    b.setMemory(0, 512, (byte) 0xFF);
    assertEquals(24, ControlMessages.encodeGatewayStatus(b, 0, 33L, 4, ControlMessages.STATUS_DISCONNECTED));
    assertEquals(33L, ControlMessages.timestampNanos(b, 0));
    assertEquals(4, ControlMessages.gatewayStatusGateway(b, 0));
    assertEquals(ControlMessages.STATUS_DISCONNECTED, ControlMessages.gatewayStatus(b, 0));
    for (int i = 13; i < 16; i++) {
      assertEquals(0, b.getByte(8 + i), "padding byte " + i);
    }
    assertEquals(0, ControlMessages.gatewayStatusAttempt(b, 0), "not reconnecting: no attempt");

    for (final int[] attempt : new int[][] {{0, 1}, {7, 7}, {200, 200}, {300, 255}}) {
      b.setMemory(0, 512, (byte) 0xFF);
      assertEquals(24, ControlMessages.encodeGatewayReconnecting(b, 0, 44L, 5, attempt[0]));
      assertEquals(ControlMessages.STATUS_RECONNECTING, ControlMessages.gatewayStatus(b, 0));
      assertEquals(5, ControlMessages.gatewayStatusGateway(b, 0));
      assertEquals(attempt[1], ControlMessages.gatewayStatusAttempt(b, 0), "attempt " + attempt[0]);
      assertEquals(0, b.getByte(8 + 14));
      assertEquals(0, b.getByte(8 + 15));
    }

    b.setMemory(0, 512, (byte) 0xFF);
    final String longText = "x".repeat(300);
    assertEquals(8 + 256, ControlMessages.encodeLoopError(b, 0, 55L, 66L, longText));
    assertEquals(55L, ControlMessages.timestampNanos(b, 0));
    assertEquals(66L, ControlMessages.loopErrorInboundCount(b, 0));
    assertEquals("x".repeat(ControlMessages.LOOP_ERROR_TEXT_CAPACITY), ControlMessages.loopErrorText(b, 0));
    b.setMemory(0, 512, (byte) 0xFF);
    ControlMessages.encodeLoopError(b, 0, 1L, 2L, "short");
    assertEquals("short", ControlMessages.loopErrorText(b, 0));
    for (int i = 8 + 18 + 5; i < 8 + 256; i++) {
      assertEquals(0, b.getByte(i), "text padding at " + i);
    }
  }

  // ---------------------------------------------------------------- the loop

  private EventLoop.Builder builder(final ManyToOneRingBuffer in, final OneToOneRingBuffer... outs) {
    final EventLoop.Builder b =
        EventLoop.builder(clock)
            .inbound(in)
            .inboundJournal((t, v, buf, o, l) -> {
              inJournal.add(new long[] {t, clock.epochNanos(), l});
              return inJournal.size();
            });
    for (final OneToOneRingBuffer out : outs) {
      b.outbound(out);
    }
    return b;
  }

  /** A message of {@code length} bytes with a QuoteRequest header (the body is not decoded). */
  private int message(final int offset, final int templateId, final int length) {
    new MessageHeaderEncoder().wrap(scratch, offset).blockLength(length - 8).templateId(templateId)
        .schemaId(QuoteRequestDecoder.SCHEMA_ID).version(QuoteRequestDecoder.SCHEMA_VERSION);
    return length;
  }

  @Test
  void theContractsLengthLimitsAreInclusiveOnBothRingsAndAtTheFullTemplateRange() {
    final ManyToOneRingBuffer in = RingBufferContract.newInbound(1 << 16);
    final OneToOneRingBuffer out = RingBufferContract.newOutbound(1 << 16);
    final EventLoop loop = builder(in, out).build();
    final List<Integer> lengths = new ArrayList<>();
    loop.register(REQUEST, (t, b, o, l) -> lengths.add(l));
    loop.register(1, (t, b, o, l) -> { });
    loop.register(65_535, (t, b, o, l) -> { });
    assertThrows(IllegalArgumentException.class, () -> loop.register(0, (t, b, o, l) -> { }));
    assertThrows(IllegalArgumentException.class, () -> loop.register(65_536, (t, b, o, l) -> { }));
    assertTrue(in.write(REQUEST, scratch, 0, message(0, REQUEST, 8)));
    assertTrue(in.write(REQUEST, scratch, 0, message(0, REQUEST, RingBufferContract.MAX_MESSAGE_LENGTH)));
    loop.doWork();
    assertEquals(List.of(8, RingBufferContract.MAX_MESSAGE_LENGTH), lengths);
    loop.publish(0, scratch, 0, message(0, REQUEST, 8));
    loop.publish(0, scratch, 0, message(0, REQUEST, RingBufferContract.MAX_MESSAGE_LENGTH));
    assertEquals(2L, loop.counters().get(LoopCounters.OUTBOUND));
    assertEquals(0L, loop.counters().get(LoopCounters.OUTBOUND_DROPPED));
    assertThrows(IllegalArgumentException.class, () -> loop.publish(0, scratch, 0, 7));
    assertThrows(IllegalArgumentException.class, () -> loop.publish(0, scratch, 0, RingBufferContract.MAX_MESSAGE_LENGTH + 1));
  }

  /** Fills a gateway ring behind the loop's back until not even a header-only message fits. */
  private void fillRing(final OneToOneRingBuffer ring) {
    for (final int length : new int[] {512, 64, 8}) {
      final int n = message(1536, REQUEST, length);
      while (ring.write(REQUEST, scratch, 1536, n)) {
        // filling
      }
    }
  }

  private static void fill(final EventLoop loop, final int gateway, final UnsafeBuffer b, final int length) {
    while (loop.counters().get(LoopCounters.OUTBOUND_DROPPED) == 0) {
      loop.publish(gateway, b, 0, length);
    }
  }

  @Test
  void backpressureNamesTheFirstDroppedTemplateAndOnlyTheGatewaysThatDropped() {
    final ManyToOneRingBuffer in = RingBufferContract.newInbound(1 << 16);
    final OneToOneRingBuffer small = RingBufferContract.newOutbound(RingBufferContract.MIN_CAPACITY);
    final OneToOneRingBuffer large = RingBufferContract.newOutbound(1 << 16);
    final EventLoop loop = builder(in, small, large).build();
    final List<String> events = new ArrayList<>();
    loop.backpressureListener(e -> events.add(e.gatewayId() + " " + e.droppedCount() + " " + e.firstTemplateId()
        + " " + e.timestampNanos()));
    final UnsafeBuffer other = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
    loop.register(REQUEST, (t, b, o, l) -> {
      final int length = message(0, REQUEST, 512);
      fillRing(small);
      new MessageHeaderEncoder().wrap(other, 0).blockLength(16).templateId(HeartbeatDecoder.TEMPLATE_ID)
          .schemaId(HeartbeatDecoder.SCHEMA_ID).version(HeartbeatDecoder.SCHEMA_VERSION);
      loop.publish(0, other, 0, 24); // the first one dropped: a heartbeat
      loop.publish(0, scratch, 0, length); // then a request
      loop.publish(1, scratch, 0, length); // gateway 1 has room
    });
    source.advance(1_000L);
    assertTrue(in.write(REQUEST, scratch, 1024, message(1024, REQUEST, 64)));
    loop.doWork();
    assertEquals(List.of("0 2 " + HeartbeatDecoder.TEMPLATE_ID + " " + clock.epochNanos()), events);
  }

  @Test
  void aDropOnTheSecondGatewayIsReportedForThatGateway() {
    final ManyToOneRingBuffer in = RingBufferContract.newInbound(1 << 16);
    final OneToOneRingBuffer first = RingBufferContract.newOutbound(1 << 16);
    final OneToOneRingBuffer second = RingBufferContract.newOutbound(RingBufferContract.MIN_CAPACITY);
    final EventLoop loop = builder(in, first, second).build();
    final List<Integer> gateways = new ArrayList<>();
    loop.backpressureListener(e -> gateways.add(e.gatewayId()));
    fillRing(second);
    loop.addAgent(() -> {
      loop.publish(1, scratch, 0, message(0, REQUEST, 64));
      return 1;
    });
    loop.doWork();
    assertEquals(List.of(1), gateways);
  }

  @Test
  void dropsByATimerOrAnAgentAreReportedInTheSameDutyCycle() {
    final ManyToOneRingBuffer in = RingBufferContract.newInbound(1 << 16);
    final OneToOneRingBuffer small = RingBufferContract.newOutbound(RingBufferContract.MIN_CAPACITY);
    final EventLoop loop = builder(in, small).build();
    final List<Integer> events = new ArrayList<>();
    loop.backpressureListener(e -> events.add(e.gatewayId()));
    final int length = message(0, REQUEST, 512);
    loop.timers().schedule(clock.nanoTime() + 1, (id, now) -> fill(loop, 0, scratch, length));
    source.advance(10);
    loop.doWork();
    assertEquals(List.of(0), events, "raised after the timers, before the next cycle");
    final boolean[] once = {true};
    loop.addAgent(() -> {
      if (once[0]) {
        once[0] = false;
        loop.publish(0, scratch, 0, length);
      }
      return 0;
    });
    loop.doWork();
    assertEquals(List.of(0, 0), events, "raised after the agent");
  }

  @Test
  void aMustDeliverMessageThatExactlyFillsTheRetryBufferIsHeld() {
    final ManyToOneRingBuffer in = RingBufferContract.newInbound(1 << 16);
    final OneToOneRingBuffer small = RingBufferContract.newOutbound(RingBufferContract.MIN_CAPACITY);
    final EventLoop loop = builder(in, small).retryOnDrop(REQUEST, 1, 65_535).retryCapacity(64).build();
    fillRing(small);
    loop.publish(0, scratch, 0, message(0, REQUEST, 56)); // 8 + 56 = 64 bytes held
    assertEquals(1L, loop.counters().get(LoopCounters.OUTBOUND_RETRY_QUEUED));
    assertEquals(0L, loop.counters().get(LoopCounters.OUTBOUND_RETRY_LOST));
    assertTrue(loop.hasHeldOutbound());
  }

  @Test
  void aLoopErrorIsStampedWithTheTimeItIsRecorded() {
    final EventLoop loop = builder(RingBufferContract.newInbound(1 << 16)).build();
    loop.doWork();
    source.advance(5_000_000_000L);
    loop.recordError(new IllegalStateException("boom"));
    final long[] last = inJournal.get(inJournal.size() - 1);
    assertEquals(ControlMessages.LOOP_ERROR_TEMPLATE_ID, last[0]);
    assertEquals(source.epochNanos(), last[1]);
    assertEquals(1L, loop.counters().get(LoopCounters.ERRORS));
  }

  @Test
  void buildersAcceptTheirSmallestValuesAndChain() throws Exception {
    final EventLoop.Builder b = EventLoop.builder(clock).readLimit(1).retryCapacity(64).retryOnDrop(1, 65_535);
    assertThrows(IllegalArgumentException.class, () -> b.readLimit(0));
    assertThrows(IllegalArgumentException.class, () -> b.retryCapacity(63));
    assertThrows(IllegalArgumentException.class, () -> b.retryOnDrop(0));
    assertThrows(IllegalArgumentException.class, () -> b.retryOnDrop(65_536));
    try (LoopJournals j = LoopJournals.create(dir, "in", "out", 64 << 10, clock, JournalMode.DIRECT, 64 << 10)) {
      final EventLoop loop = b.journals(j).build().addAgent(() -> 0).backpressureListener(e -> { });
      assertEquals(1, loop.doWork() + 1);
      assertEquals(0L, j.rollWaits() + j.producerWaits());
    }
    try (var files = Files.list(dir)) {
      for (final Path f : files.toList()) {
        Files.delete(f); // closed journals release their files
      }
    }
    final LoopRunner.Builder rb = LoopRunner.builder(EventLoop.builder(clock).build()).cpu(-1).closeTimeoutMillis(1);
    assertThrows(IllegalArgumentException.class, () -> rb.closeTimeoutMillis(0));
  }

  // ---------------------------------------------------------------- publisher and faults

  @Test
  void anAbortedClaimFreesTheRingAndLengthsAreCheckedInclusively() {
    final ManyToOneRingBuffer ring = RingBufferContract.newInbound(RingBufferContract.MIN_CAPACITY);
    final InboundPublisher p = new InboundPublisher(ring);
    final int index = p.tryClaim(REQUEST, 64);
    assertTrue(index >= 0);
    assertEquals(0L, p.rejectedCount());
    p.abort(index);
    assertTrue(p.offer(scratch, 0, message(0, REQUEST, 8)));
    assertTrue(p.offer(scratch, 0, message(0, REQUEST, RingBufferContract.MAX_MESSAGE_LENGTH)));
    final List<Integer> read = new ArrayList<>();
    ring.read((t, b, i, l) -> read.add(l));
    assertEquals(List.of(8, RingBufferContract.MAX_MESSAGE_LENGTH), read, "the aborted claim is skipped");
    assertThrows(IllegalArgumentException.class, () -> p.offer(scratch, 0, 7));
    assertThrows(IllegalArgumentException.class, () -> p.tryClaim(REQUEST, RingBufferContract.MAX_MESSAGE_LENGTH + 1));
    assertEquals(2L, p.publishedCount());
  }

  @Test
  void aDelayOfOneMessageLagsTheStreamByExactlyOneAndNoFaultsLagsNothing() {
    final UnsafeBuffer msg = new UnsafeBuffer(new byte[16]);
    final List<Integer> delayed = new ArrayList<>();
    final FaultInjector d = new FaultInjector(new FaultPlan(0, 0, 1, 0, 1, 5), (b, o, l) -> delayed.add(b.getInt(o)));
    final List<Integer> clean = new ArrayList<>();
    final FaultInjector c = new FaultInjector(FaultPlan.none(), (b, o, l) -> clean.add(b.getInt(o)));
    for (int i = 1; i <= 5; i++) {
      msg.putInt(0, i);
      d.offer(msg, 0, 16);
      c.offer(msg, 0, 16);
    }
    assertEquals(List.of(1, 2, 3, 4), delayed);
    assertEquals(1, d.heldCount());
    assertEquals(List.of(1, 2, 3, 4, 5), clean);
    assertEquals(0, c.heldCount());
  }

  @Test
  void faultRatesMaySumToOneButNoMore() {
    assertEquals(0.5, new FaultPlan(0.5, 0.5, 0, 0, 1, 1).dropRate(), 0.0);
    assertThrows(IllegalArgumentException.class, () -> new FaultPlan(0.6, 0.5, 0, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new FaultPlan(0, 0.6, 0.5, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new FaultPlan(0, 0, 0.6, 0.5, 1, 1));
  }

  // ---------------------------------------------------------------- replay and runner

  @Test
  void aReplayThatProducesNothingMismatchesAtTheFirstRecordedMessage() {
    final QuoteApp app = new QuoteApp(false);
    try (TestRig rig = new TestRig(dir, app, 1 << 16, RingBufferContract.DEFAULT_OUTBOUND_CAPACITY)) {
      for (long rfq = 1; rfq <= 3; rfq++) {
        rig.offerRequest(rfq);
      }
      rig.cycle();
      assertEquals(3L, app.quotes);
    }
    final ReplayResult r = LoopReplay.run(dir, "in", "out", b -> b.build());
    assertEquals(0L, r.outboundProduced());
    assertEquals(3L, r.outboundRecorded());
    assertEquals(1L, r.firstMismatch());
    assertFalse(r.matches());
  }

  @Test
  void theRunnerReportsItsThreadAndInterruptsAHookThatWillNotReturn() throws Exception {
    final EventLoop loop = EventLoop.builder(clock).build();
    final CountDownLatch never = new CountDownLatch(1);
    final long[] seen = new long[1];
    final LoopRunner runner =
        LoopRunner.builder(loop)
            .name("edge-runner")
            .daemon(true)
            .idleStrategy(new YieldingIdleStrategy())
            .closeTimeoutMillis(50)
            .startOfDay(l -> {
              seen[0] = Thread.currentThread().threadId();
              try {
                never.await();
              } catch (final InterruptedException e) {
                throw new IllegalStateException("interrupted", e);
              }
            })
            .build()
            .start();
    assertFalse(runner.awaitTermination(1, TimeUnit.MILLISECONDS), "blocked in its start-of-day hook");
    assertTrue(runner.isAlive());
    final Thread t = Thread.getAllStackTraces().keySet().stream()
        .filter(x -> x.threadId() == runner.threadId()).findFirst().orElseThrow();
    assertTrue(t.isDaemon());
    assertEquals("edge-runner", t.getName());
    runner.close(); // waits 50 ms, then interrupts the hook
    assertTrue(runner.awaitTermination(10, TimeUnit.SECONDS));
    assertFalse(runner.isAlive());
    assertEquals(runner.threadId(), seen[0]);
    assertTrue(runner.failure() instanceof IllegalStateException, String.valueOf(runner.failure()));
    assertTrue(runner.failure().getCause() instanceof InterruptedException);
  }
}
