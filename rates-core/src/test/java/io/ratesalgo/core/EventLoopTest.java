package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.HeartbeatDecoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.junit.jupiter.api.Test;

class EventLoopTest {

  private final ManualClock source = new ManualClock();
  private final EventClock clock = new EventClock(source);
  private final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(1 << 16);
  private final OneToOneRingBuffer outbound = RingBufferContract.newOutbound(RingBufferContract.MIN_CAPACITY);
  private final InboundPublisher publisher = new InboundPublisher(inbound);
  private final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
  private final List<int[]> inJournal = new ArrayList<>();
  private final List<int[]> outJournal = new ArrayList<>();

  private EventLoop loop() {
    return EventLoop.builder(clock)
        .inbound(inbound)
        .outbound(outbound)
        .inboundJournal(
            (t, v, b, o, l) -> {
              inJournal.add(new int[] {t, v, l, (int) clock.epochNanos()});
              return inJournal.size();
            })
        .outboundJournal(
            (t, v, b, o, l) -> {
              outJournal.add(new int[] {t, v, l});
              return outJournal.size();
            })
        .readLimit(64)
        .build();
  }

  @Test
  void dispatchesByTemplateAndJournalsEveryInboundMessageFirst() {
    final EventLoop loop = loop();
    final List<Long> seen = new ArrayList<>();
    loop.register(
        QuoteRequestDecoder.TEMPLATE_ID,
        (t, b, o, l) -> {
          assertEquals(seen.size() + 1, inJournal.size());
          seen.add(Msgs.rfqId(b, o));
        });
    for (long i = 1; i <= 5; i++) {
      assertTrue(publisher.offer(scratch, 0, Msgs.quoteRequest(scratch, 0, i)));
    }
    assertTrue(publisher.offer(scratch, 0, Msgs.heartbeat(scratch, 0, 9)));
    assertEquals(6, loop.doWork());
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L), seen);
    assertEquals(6, inJournal.size());
    assertEquals(QuoteRequestDecoder.TEMPLATE_ID, inJournal.get(0)[0]);
    assertEquals(HeartbeatDecoder.TEMPLATE_ID, inJournal.get(5)[0]);
    assertEquals(6L, loop.counters().get(LoopCounters.INBOUND));
    assertEquals(1L, loop.counters().get(LoopCounters.UNHANDLED));
    assertEquals(6L, publisher.publishedCount());
    assertEquals(0, loop.doWork());
  }

  @Test
  void everyMessageInADutyCycleCarriesTheLatchedTime() {
    final EventLoop loop = loop();
    loop.register(QuoteRequestDecoder.TEMPLATE_ID, (t, b, o, l) -> source.advance(1_000L));
    publisher.offer(scratch, 0, Msgs.quoteRequest(scratch, 0, 1));
    publisher.offer(scratch, 0, Msgs.quoteRequest(scratch, 0, 2));
    final long before = source.epochNanos();
    loop.doWork();
    assertEquals(inJournal.get(0)[3], inJournal.get(1)[3]);
    assertEquals((int) before, inJournal.get(0)[3]);
    assertEquals(before, loop.clock().epochNanos());
  }

  @Test
  void publishJournalsAndWritesToTheGatewayRing() {
    final EventLoop loop = loop();
    loop.register(
        QuoteRequestDecoder.TEMPLATE_ID,
        (t, b, o, l) -> loop.publish(0, scratch, 256, Msgs.quoteRequest(scratch, 256, 77)));
    publisher.offer(scratch, 0, Msgs.quoteRequest(scratch, 0, 1));
    loop.doWork();
    assertEquals(1, outJournal.size());
    final List<Long> read = new ArrayList<>();
    outbound.read((t, b, i, l) -> read.add(Msgs.rfqId(b, i)));
    assertEquals(List.of(77L), read);
    assertEquals(1L, loop.counters().get(LoopCounters.OUTBOUND));
    assertEquals(1, loop.gatewayCount());
    assertFalse(loop.isReplay());
  }

  @Test
  void fullOutboundRingDropsCountsJournalsAndRaisesOneEventAfterTheHandler() {
    final EventLoop loop = loop();
    final int[] published = new int[1];
    final List<String> order = new ArrayList<>();
    loop.register(
        QuoteRequestDecoder.TEMPLATE_ID,
        (t, b, o, l) -> {
          for (int i = 0; i < 200; i++) {
            loop.publish(0, scratch, 256, Msgs.quoteRequest(scratch, 256, i));
            published[0]++;
          }
          order.add("handler done");
        });
    loop.backpressureListener(
        e -> {
          order.add("listener " + e.gatewayId() + " " + e.droppedCount() + " " + e.firstTemplateId());
          assertTrue(e.toString().contains("gateway=0"));
        });
    publisher.offer(scratch, 0, Msgs.quoteRequest(scratch, 0, 1));
    loop.doWork();
    final long dropped = loop.counters().get(LoopCounters.OUTBOUND_DROPPED);
    assertTrue(dropped > 0);
    assertEquals(200, outJournal.size());
    assertEquals(List.of("handler done", "listener 0 " + dropped + " " + QuoteRequestDecoder.TEMPLATE_ID), order);
    assertEquals(1L, loop.counters().get(LoopCounters.BACKPRESSURE_EVENTS));
    final int[] bp = inJournal.get(1);
    assertEquals(ControlMessages.BACKPRESSURE_TEMPLATE_ID, bp[0]);
  }

  @Test
  void firedTimersJournalOneClockTickPerCycle() {
    final EventLoop loop = loop();
    final List<Long> fired = new ArrayList<>();
    loop.timers().schedule(clock.nanoTime() + 2_000_000L, (id, now) -> fired.add(id));
    loop.timers().schedule(clock.nanoTime() + 2_000_000L, (id, now) -> fired.add(id));
    assertEquals(0, loop.doWork());
    source.advance(2_000_000L);
    assertEquals(2, loop.doWork());
    assertEquals(2, fired.size());
    assertEquals(1, inJournal.size());
    assertEquals(HeartbeatDecoder.TEMPLATE_ID, inJournal.get(0)[0]);
    assertEquals(1L, loop.counters().get(LoopCounters.TIMER_TICKS));
  }

  @Test
  void agentsRunEachCycleAfterTheRings() {
    final EventLoop loop = loop();
    final List<String> order = new ArrayList<>();
    loop.register(QuoteRequestDecoder.TEMPLATE_ID, (t, b, o, l) -> order.add("msg"));
    loop.addAgent(
        () -> {
          order.add("agent");
          return 3;
        });
    publisher.offer(scratch, 0, Msgs.quoteRequest(scratch, 0, 1));
    assertEquals(4, loop.doWork());
    assertEquals(List.of("msg", "agent"), order);
  }

  @Test
  void gatewayStatusIsAcceptedFromARing() {
    final EventLoop loop = loop();
    final List<Byte> statuses = new ArrayList<>();
    loop.register(
        ControlMessages.GATEWAY_STATUS_TEMPLATE_ID,
        (t, b, o, l) -> statuses.add(ControlMessages.gatewayStatus(b, o)));
    publisher.offer(
        scratch, 0, ControlMessages.encodeGatewayStatus(scratch, 0, 5L, 2, ControlMessages.STATUS_DISCONNECTED));
    loop.doWork();
    assertEquals(List.of(ControlMessages.STATUS_DISCONNECTED), statuses);
  }

  @Test
  void contractBreachesThrow() {
    final EventLoop loop = loop();
    // reserved clock-tick heartbeat
    publisher.offer(scratch, 0, Msgs.heartbeat(scratch, 0, EventLoop.CLOCK_TICK_SOURCE_ID));
    assertTrue(assertThrows(IllegalStateException.class, loop::doWork).getMessage().contains("loop only"));
    // loop-only control message
    publisher.offer(scratch, 0, ControlMessages.encodeBackpressure(scratch, 0, 1L, 1L, 0, 11));
    assertThrows(IllegalStateException.class, loop::doWork);
    // record type differs from template id
    final int len = Msgs.quoteRequest(scratch, 0, 1);
    assertTrue(inbound.write(99, scratch, 0, len));
    assertTrue(assertThrows(IllegalStateException.class, loop::doWork).getMessage().contains("record type"));
    // wrong schema id
    Msgs.quoteRequest(scratch, 0, 1);
    scratch.putShort(4, (short) 7, ByteOrder.LITTLE_ENDIAN);
    publisher.offer(scratch, 0, len);
    assertTrue(assertThrows(IllegalStateException.class, loop::doWork).getMessage().contains("schema id 7"));
    // wrong control schema version
    ControlMessages.encodeGatewayStatus(scratch, 0, 1L, 0, ControlMessages.STATUS_CONNECTED);
    scratch.putShort(6, (short) 3, ByteOrder.LITTLE_ENDIAN);
    publisher.offer(scratch, 0, 24);
    assertThrows(IllegalStateException.class, loop::doWork);
    // too short for a header
    assertTrue(inbound.write(1, scratch, 0, 4));
    assertThrows(IllegalArgumentException.class, loop::doWork);
    assertEquals(0L, loop.counters().get(LoopCounters.INBOUND));
  }

  @Test
  void publishRejectsUnknownGatewaysAndBadLengths() {
    final EventLoop loop = loop();
    final int len = Msgs.quoteRequest(scratch, 0, 1);
    assertThrows(IllegalArgumentException.class, () -> loop.publish(1, scratch, 0, len));
    assertThrows(IllegalArgumentException.class, () -> loop.publish(-1, scratch, 0, len));
    assertThrows(IllegalArgumentException.class, () -> loop.publish(0, scratch, 0, 4));
    assertThrows(IllegalArgumentException.class, () -> loop.publish(0, scratch, 0, 2000));
    assertThrows(IllegalArgumentException.class, () -> publisher.offer(scratch, 0, 4));
    assertThrows(IllegalArgumentException.class, () -> publisher.tryClaim(10, 5000));
  }

  @Test
  void registrationAndBuilderRules() {
    final EventLoop loop = loop();
    final MessageHandler h = (t, b, o, l) -> {};
    assertSame(loop, loop.register(10, h));
    assertThrows(IllegalArgumentException.class, () -> loop.register(10, h));
    assertThrows(IllegalArgumentException.class, () -> loop.register(0, h));
    assertThrows(IllegalArgumentException.class, () -> loop.register(1 << 16, h));
    assertThrows(IllegalArgumentException.class, () -> EventLoop.builder(clock).readLimit(0));
    final EventLoop.Builder replay = new EventLoop.Builder(clock, true);
    assertTrue(replay.isReplay());
    assertSame(clock, replay.clock());
    assertThrows(IllegalStateException.class, () -> replay.inbound(inbound));
    assertThrows(IllegalStateException.class, () -> replay.outbound(outbound));
    assertThrows(IllegalStateException.class, () -> replay.inboundJournal(JournalSink.NONE));
    assertThrows(IllegalStateException.class, () -> replay.outboundJournal(JournalSink.NONE));
    assertFalse(EventLoop.builder(clock).isReplay());
    assertEquals(0L, JournalSink.NONE.append(1, 0, scratch, 0, 8));
  }

  @Test
  void recordErrorJournalsALoopError() {
    final List<String> texts = new ArrayList<>();
    final EventLoop loop =
        EventLoop.builder(clock)
            .inboundJournal(
                (t, v, b, o, l) -> {
                  assertEquals(ControlMessages.LOOP_ERROR_TEMPLATE_ID, t);
                  texts.add(ControlMessages.loopErrorText(b, o));
                  assertEquals(0L, ControlMessages.loopErrorInboundCount(b, o));
                  return 1L;
                })
            .build();
    loop.recordError(new IllegalStateException("boom"));
    assertEquals(List.of("java.lang.IllegalStateException: boom"), texts);
    assertEquals(1L, loop.counters().get(LoopCounters.ERRORS));
  }

  @Test
  void inPlaceClaimPublishesAndAbortReleases() {
    final EventLoop loop = loop();
    final List<Long> seen = new ArrayList<>();
    loop.register(QuoteRequestDecoder.TEMPLATE_ID, (t, b, o, l) -> seen.add(Msgs.rfqId(b, o)));
    final int index = publisher.tryClaim(QuoteRequestDecoder.TEMPLATE_ID, Msgs.QUOTE_REQUEST_LENGTH);
    Msgs.quoteRequest(publisher.buffer(), index, 41);
    publisher.commit(index);
    final int aborted = publisher.tryClaim(QuoteRequestDecoder.TEMPLATE_ID, Msgs.QUOTE_REQUEST_LENGTH);
    publisher.abort(aborted);
    loop.doWork();
    assertEquals(List.of(41L), seen);
    assertEquals(1L, publisher.publishedCount());
  }

  @Test
  void fullInboundRingIsCountedByThePublisher() {
    final ManyToOneRingBuffer small = RingBufferContract.newInbound(RingBufferContract.MIN_CAPACITY);
    final InboundPublisher p = new InboundPublisher(small);
    int ok = 0;
    for (int i = 0; i < 1000; i++) {
      if (p.offer(scratch, 0, Msgs.quoteRequest(scratch, 0, i))) {
        ok++;
      }
    }
    assertEquals(ok, p.publishedCount());
    assertEquals(1000 - ok, p.rejectedCount());
    assertTrue(p.tryClaim(QuoteRequestDecoder.TEMPLATE_ID, Msgs.QUOTE_REQUEST_LENGTH) < 0);
    assertEquals(1001 - ok, p.rejectedCount());
  }

  @Test
  void quoteAppAnswersOnGatewayZero() {
    final QuoteApp app = new QuoteApp(false);
    final EventLoop loop =
        app.create(EventLoop.builder(clock).inbound(inbound).outbound(outbound).outbound(outbound));
    publisher.offer(scratch, 0, Msgs.quoteRequest(scratch, 0, 5));
    loop.doWork();
    final List<Long> rfqs = new ArrayList<>();
    final QuoteDecoder q = new QuoteDecoder();
    outbound.read(
        (t, b, i, l) -> {
          q.wrap(b, i + 8, QuoteDecoder.BLOCK_LENGTH, QuoteDecoder.SCHEMA_VERSION);
          rfqs.add(q.rfqId());
          assertTrue(q.askPrice().mantissa() > q.bidPrice().mantissa());
        });
    assertEquals(List.of(5L), rfqs);
  }
}
