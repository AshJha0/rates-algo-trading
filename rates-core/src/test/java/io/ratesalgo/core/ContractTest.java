package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class ContractTest {

  private final UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(512));

  @Test
  void ringCapacitiesMustBePowersOfTwoAboveTheMinimum() {
    assertEquals(RingBufferContract.MIN_CAPACITY, RingBufferContract.newInbound(RingBufferContract.MIN_CAPACITY).capacity());
    assertEquals(
        RingBufferContract.MAX_MESSAGE_LENGTH,
        RingBufferContract.newOutbound(RingBufferContract.MIN_CAPACITY).maxMsgLength());
    assertThrows(IllegalArgumentException.class, () -> RingBufferContract.newInbound(4096));
    assertThrows(IllegalArgumentException.class, () -> RingBufferContract.newOutbound(10_000));
  }

  @Test
  void controlMessagesRoundTrip() {
    final int bp = ControlMessages.encodeBackpressure(b, 16, 99L, 7L, 3, 11);
    assertEquals(32, bp);
    assertEquals(ControlMessages.BACKPRESSURE_TEMPLATE_ID, RingBufferContract.templateId(b, 16));
    assertEquals(ControlMessages.SCHEMA_ID, RingBufferContract.schemaId(b, 16));
    assertEquals(0, RingBufferContract.version(b, 16));
    assertEquals(99L, ControlMessages.timestampNanos(b, 16));
    assertEquals(7L, ControlMessages.backpressureDropped(b, 16));
    assertEquals(3, ControlMessages.backpressureGateway(b, 16));
    assertEquals(11, ControlMessages.backpressureFirstTemplate(b, 16));

    assertEquals(24, ControlMessages.encodeGatewayStatus(b, 0, 5L, 4, ControlMessages.STATUS_CONNECTED));
    assertEquals(4, ControlMessages.gatewayStatusGateway(b, 0));
    assertEquals(ControlMessages.STATUS_CONNECTED, ControlMessages.gatewayStatus(b, 0));

    final String longText = "x".repeat(300) + "é";
    assertEquals(264, ControlMessages.encodeLoopError(b, 0, 1L, 42L, longText));
    assertEquals(ControlMessages.LOOP_ERROR_TEXT_CAPACITY, ControlMessages.loopErrorText(b, 0).length());
    assertEquals(42L, ControlMessages.loopErrorInboundCount(b, 0));
    ControlMessages.encodeLoopError(b, 0, 1L, 0L, "café");
    assertEquals("caf?", ControlMessages.loopErrorText(b, 0));
  }

  @Test
  void counterNames() {
    for (int i = 0; i < LoopCounters.COUNT; i++) {
      assertEquals(false, LoopCounters.name(i).isEmpty());
    }
    assertThrows(IllegalArgumentException.class, () -> LoopCounters.name(LoopCounters.COUNT));
  }
}
