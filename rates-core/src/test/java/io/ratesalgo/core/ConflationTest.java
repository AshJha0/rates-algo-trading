package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Conflated curve inputs (review finding L10, DECISIONS.md DR-R6-L05): held back per key, the
 * newest only, dispatched after the rest of the ring at a bounded number per cycle, journaled when
 * dispatched, so that replay reproduces the order the handlers saw.
 */
class ConflationTest {

  /** CurveInput key: kind and instrument id, five bytes from body offset 4. */
  static final int KEY_OFFSET = MessageHeaderEncoder.ENCODED_LENGTH + CurveInputEncoder.kindEncodingOffset();

  static final int KEY_LENGTH = 5;

  @TempDir Path dir;

  /** What the handlers saw, in order: "rfq:N" or "curve:instrument=value". */
  private final List<String> seen = new ArrayList<>();

  private LoopFactory factory(final boolean conflate, final int perCycle) {
    return b -> {
      if (conflate) {
        b.conflate(CurveInputDecoder.TEMPLATE_ID, KEY_OFFSET, KEY_LENGTH).conflatedPerCycle(perCycle).conflationSlots(2, 128);
      }
      final EventLoop loop = b.build();
      final CurveInputDecoder curve = new CurveInputDecoder();
      final io.ratesalgo.messages.sbe.MessageHeaderDecoder header = new io.ratesalgo.messages.sbe.MessageHeaderDecoder();
      loop.register(QuoteRequestDecoder.TEMPLATE_ID, (t, buf, o, l) -> {
        seen.add("rfq:" + Msgs.rfqId(buf, o));
        loop.publish(0, buf, o, l);
      });
      loop.register(CurveInputDecoder.TEMPLATE_ID, (t, buf, o, l) -> {
        curve.wrapAndApplyHeader(buf, o, header);
        seen.add("curve:" + curve.instrumentId() + "=" + curve.value().mantissa());
      });
      return loop;
    };
  }

  private static int curveInput(final UnsafeBuffer b, final int instrument, final long value) {
    final CurveInputEncoder e = new CurveInputEncoder();
    e.wrapAndApplyHeader(b, 0, new MessageHeaderEncoder())
        .curveId(1)
        .kind(CurveInputKind.ParSwap)
        .instrumentId(instrument)
        .tenor("5Y")
        .timestampNanos(value);
    e.value().mantissa(value);
    return MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength();
  }

  private static void offerCurve(final TestRig rig, final int instrument, final long value) {
    final int length = curveInput(rig.scratch, instrument, value);
    assertTrue(rig.publisher.offer(rig.scratch, 0, length));
  }

  /** A burst of curve inputs with two RFQs in it. */
  private static void burst(final TestRig rig) {
    offerCurve(rig, 11, 1);
    offerCurve(rig, 12, 1);
    assertTrue(rig.offerRequest(1));
    offerCurve(rig, 11, 2);
    assertTrue(rig.offerRequest(2));
    offerCurve(rig, 11, 3);
  }

  @Test
  void rfqsGoFirstAndOnlyTheNewestCurveInputPerKeyIsHandledAndJournaled() {
    try (TestRig rig = new TestRig(dir, factory(true, 1), 1 << 16, 1 << 16, JournalMode.DIRECT)) {
      burst(rig);
      assertTrue(rig.cycle() > 0);
      assertEquals(List.of("rfq:1", "rfq:2", "curve:11=3"), seen, "one held input per cycle, after the ring");
      assertEquals(1, rig.loop.heldInputs());
      assertEquals(2, rig.loop.counters().get(LoopCounters.CONFLATED), "values 1 and 2 of instrument 11 replaced");
      assertTrue(rig.cycle() > 0);
      assertEquals(List.of("rfq:1", "rfq:2", "curve:11=3", "curve:12=1"), seen);
      assertEquals(0, rig.loop.heldInputs());
      assertEquals(0, rig.cycle(), "nothing held, nothing to do");
      assertEquals(4, rig.loop.counters().get(LoopCounters.INBOUND), "only dispatched inputs count");
    }
    seen.clear();
    final ReplayResult replay = LoopReplay.run(dir, "in", "out", factory(true, 1));
    assertTrue(replay.matches(), replay.toString());
    assertEquals(4, replay.inboundFrames(), "the journal holds what was dispatched, in that order");
    assertEquals(List.of("rfq:1", "rfq:2", "curve:11=3", "curve:12=1"), seen, "replay saw the live order");
  }

  @Test
  void withoutConflationEveryInputIsHandledWhereItStands() {
    try (TestRig rig = new TestRig(dir, factory(false, 1), 1 << 16, 1 << 16, JournalMode.DIRECT)) {
      burst(rig);
      rig.cycle();
      assertEquals(List.of("curve:11=1", "curve:12=1", "rfq:1", "curve:11=2", "rfq:2", "curve:11=3"), seen);
      assertEquals(0, rig.loop.heldInputs());
    }
  }

  @Test
  void aWiderBudgetDispatchesMorePerCycleAndRingModeReplaysTheSame() {
    try (TestRig rig = new TestRig(dir, factory(true, 8), 1 << 16, 1 << 16, JournalMode.RING)) {
      burst(rig);
      rig.cycle();
      assertEquals(List.of("rfq:1", "rfq:2", "curve:11=3", "curve:12=1"), seen);
    }
    seen.clear();
    assertTrue(LoopReplay.run(dir, "in", "out", factory(true, 8)).matches());
  }

  @Test
  void anInputWithNoFreeSlotOrTooLongForOneIsDispatchedAtOnce() {
    try (TestRig rig = new TestRig(dir, factory(true, 1), 1 << 16, 1 << 16, JournalMode.DIRECT)) {
      offerCurve(rig, 11, 1);
      offerCurve(rig, 12, 1);
      offerCurve(rig, 13, 1); // two slots: this one is dispatched where it stands
      rig.cycle();
      assertEquals(List.of("curve:13=1", "curve:11=1"), seen);
    }
    final EventLoop.Builder small =
        EventLoop.builder(new EventClock(new io.ratesalgo.common.time.ManualClock()))
            .conflate(CurveInputDecoder.TEMPLATE_ID, KEY_OFFSET, KEY_LENGTH)
            .conflationSlots(4, 16);
    final EventLoop loop = small.build();
    final List<String> got = new ArrayList<>();
    loop.register(CurveInputDecoder.TEMPLATE_ID, (t, b, o, l) -> got.add("curve"));
    final org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer ring = RingBufferContract.newInbound(RingBufferContract.MIN_CAPACITY);
    final EventLoop withRing =
        EventLoop.builder(new EventClock(new io.ratesalgo.common.time.ManualClock()))
            .inbound(ring)
            .conflate(CurveInputDecoder.TEMPLATE_ID, KEY_OFFSET, KEY_LENGTH)
            .conflationSlots(4, 16)
            .build();
    withRing.register(CurveInputDecoder.TEMPLATE_ID, (t, b, o, l) -> got.add("curve"));
    final UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
    final int length = curveInput(b, 11, 1);
    assertTrue(new InboundPublisher(ring).offer(b, 0, length));
    withRing.doWork();
    assertEquals(List.of("curve"), got, "longer than a 16-byte slot: not held");
    assertEquals(0, withRing.heldInputs());
    assertEquals(0, loop.heldInputs());
  }

  @Test
  void theBuilderRefusesBadConflationSettings() {
    final EventLoop.Builder b = EventLoop.builder(new EventClock(new io.ratesalgo.common.time.ManualClock()));
    assertThrows(IllegalArgumentException.class, () -> b.conflate(0, KEY_OFFSET, 4));
    assertThrows(IllegalArgumentException.class, () -> b.conflate(5, 2, 4), "the key is after the header");
    assertThrows(IllegalArgumentException.class, () -> b.conflate(5, KEY_OFFSET, 9));
    assertThrows(IllegalArgumentException.class, () -> b.conflate(5, KEY_OFFSET, 0));
    assertThrows(IllegalArgumentException.class, () -> b.conflatedPerCycle(0));
    assertThrows(IllegalArgumentException.class, () -> b.conflationSlots(0, 64));
    assertThrows(IllegalArgumentException.class, () -> b.conflationSlots(4, 2));
  }
}
