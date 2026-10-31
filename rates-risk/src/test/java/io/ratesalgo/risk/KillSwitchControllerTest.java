package io.ratesalgo.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KillSwitchControllerTest {

  private final ManualClock clock = new ManualClock(5_000L);
  private final RecordingOutboundSink control = new RecordingOutboundSink();
  private final KillSwitchController kill = new KillSwitchController(control, clock);

  @Test
  void stateMachine() {
    final List<String> calls = new ArrayList<>();
    kill.register((r, t) -> calls.add("quotes " + r + "@" + t));
    kill.register((r, t) -> calls.add("orders " + r));
    assertTrue(kill.isActive());
    assertThrows(IllegalStateException.class, () -> kill.requestReset(1));

    assertTrue(kill.trigger(KillReason.FeedGap, 0, 7));
    assertEquals(List.of("quotes FeedGap@5000", "orders FeedGap"), calls);
    assertEquals(KillState.KILLED, kill.state());
    assertEquals(1, control.count(KillSwitchDecoder.TEMPLATE_ID));
    final KillSwitchDecoder d = new KillSwitchDecoder();
    d.wrap(control.message(0), MessageHeaderEncoder.ENCODED_LENGTH, KillSwitchDecoder.BLOCK_LENGTH, 0);
    assertEquals(KillReason.FeedGap, d.reason());
    assertEquals(7, d.requestedBy());
    assertEquals(5_000L, d.timestampNanos());

    assertFalse(kill.trigger(KillReason.Manual, 0, 7), "already killed");
    assertEquals(1, control.size());
    assertThrows(IllegalStateException.class, () -> kill.approveReset(2, true));

    kill.requestReset(1);
    assertEquals(KillState.RESET_PENDING, kill.state());
    assertFalse(kill.isActive());
    assertFalse(kill.approveReset(1, true), "four eyes");
    assertFalse(kill.approveReset(2, false), "inputs unhealthy");
    assertEquals(KillState.RESET_PENDING, kill.state());

    assertTrue(kill.trigger(KillReason.StaleReference, 0, 0), "a kill during a pending reset is a full kill");
    assertEquals(KillState.KILLED, kill.state());
    assertEquals(2, control.count(KillSwitchDecoder.TEMPLATE_ID));
    assertEquals(4, calls.size());

    kill.requestReset(1);
    assertTrue(kill.approveReset(2, true));
    assertTrue(kill.isActive());
    assertEquals(2, kill.killCount());
    assertEquals(3, kill.triggerRequests());
    assertEquals(KillReason.StaleReference, kill.lastReason());
  }

  @Test
  void listenerCapacityIsBounded() {
    for (int i = 0; i < 16; i++) {
      kill.register((r, t) -> {});
    }
    assertThrows(IllegalStateException.class, () -> kill.register((r, t) -> {}));
  }

  @Test
  void tokenBucket() {
    final TokenBucket b = new TokenBucket(2.0, 1.5, 0L);
    assertEquals(3.0, b.capacity(), 0.0);
    for (int i = 0; i < 3; i++) {
      assertTrue(b.available(0L));
      b.take();
    }
    assertFalse(b.available(0L));
    assertTrue(b.available(500_000_000L));
    assertEquals(1.0, b.tokens(500_000_000L), 1e-12);
    b.takeExempt(500_000_000L);
    b.takeExempt(500_000_000L);
    assertEquals(-1.0, b.tokens(500_000_000L), 1e-12);
    assertEquals(3.0, b.tokens(10_000_000_000L), 1e-12, "refill caps at capacity");
    assertTrue(b.available(9_000_000_000L), "time going backwards does not refill or fail");
  }
}
