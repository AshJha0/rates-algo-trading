package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.gateway.VenueSession;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wiring line in {@link TradingProcess} that halts the process when a session message cannot
 * be delivered to a loop. A fill or a reject the loop never received leaves its view of the venue
 * wrong for good, so the process must stop trading on it: withdraw from the gateway side, log out
 * and say why.
 *
 * <p>The loop is held still (the process is stepped, and only its gateways are driven) with its
 * inbound ring full, and the D2C venue is let run so that it sends RFQs over FIX. The gateway
 * waits its bounded wait for room, gives the message up, and the process must halt. Without the
 * wiring line the message is only counted and this test fails at the first assertion on the halt.
 */
class UndeliveredHaltTest {

  private static final long SECOND = 1_000_000_000L;
  private static final long STEP = 10_000_000L;
  private static final String REASON = "a session message could not be delivered to the loop";

  @TempDir Path dir;

  /** Drives the gateways, and nothing else, until the condition holds. */
  private static void driveUntil(final TradingProcess p, final BooleanSupplier done, final String what) {
    final long deadline = System.nanoTime() + 120 * SECOND;
    while (!done.getAsBoolean()) {
      assertTrue(System.nanoTime() - deadline < 0, "timed out waiting for " + what + ": " + p.statusJson());
      if (p.driveGateways() == 0) {
        Thread.onSpinWait();
      }
    }
  }

  @Test
  void aSessionMessageTheLoopCannotTakeHaltsWithdrawsLogsOutAndSaysWhy() {
    final TradingProcess p = TradingProcess.start(new TradingProcess.Options(dir, 7L, DeskSettings.defaults()));
    try {
      p.step(6 * 60 * SECOND, STEP); // across the 08:30 peak: quotes sent, hedges routed
      assertTrue(p.group(0).quotesSent() > 0);
      assertNull(p.haltReason());
      assertNull(p.notReadyReason());

      // the loop stops reading and its ring fills: from here on only the gateways are driven
      final RingBuffer ring = p.inboundRing(0);
      final UnsafeBuffer beat = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
      final HeartbeatEncoder heartbeat = new HeartbeatEncoder();
      heartbeat.wrapAndApplyHeader(beat, 0, new MessageHeaderEncoder()).sourceId(12345).timestampNanos(1L);
      final int length = MessageHeaderEncoder.ENCODED_LENGTH + heartbeat.encodedLength();
      int filled = 0;
      while (ring.write(HeartbeatEncoder.TEMPLATE_ID, beat, 0, length)) {
        filled++;
      }
      assertTrue(filled > 0);

      // the venue goes on: RFQs arrive over FIX and have nowhere to go
      for (int second = 0; second < 600 && p.haltReason() == null; second++) {
        p.venues().advance(p.clock(), SECOND);
        p.venues().onTime();
        for (int i = 0; i < 200 && p.haltReason() == null; i++) {
          p.driveGateways();
        }
      }
      assertEquals(REASON, p.haltReason(), "the undelivered message halted the process");

      // the halt withdraws from the gateway side and logs out; the loop is still not running
      driveUntil(p, p::withdrawn, "the sessions withdrawing");
      driveUntil(p,
          () -> !p.venues().d2c().loggedOn().contains(AppIds.COMP_ID)
              && !p.venues().clob().loggedOn().contains(AppIds.COMP_ID),
          "the venues seeing the logout");
      assertTrue(p.venues().clob().liveOrders(AppIds.COMP_ID).isEmpty(), "nothing of ours rests on the CLOB");
      assertEquals(VenueSession.WITHDRAWN, p.d2cSession().haltState());
      assertEquals(VenueSession.WITHDRAWN, p.clobSession().haltState());
      assertTrue(p.d2cSession().isHalted() && p.clobSession().isHalted());
      assertFalse(p.d2cSession().isActive() || p.clobSession().isActive(), "logged out");
      assertFalse(p.socketsClosed(), "an orderly logout, not a dropped socket");
      assertFalse(p.d2cSession().reconnectPending() || p.clobSession().reconnectPending(),
          "a halted process does not reconnect");

      // and it says so
      assertEquals("halted: " + REASON, p.notReadyReason());
      final String status = p.statusJson();
      assertTrue(status.contains("\"halted\":true") && status.contains("\"withdrawn\":true"), status);
      assertTrue(status.contains("\"haltReason\":\"" + REASON + "\""), status);
      assertTrue(status.contains("\"quoting\":false") && status.contains("\"killSwitch\":true"), status);
      assertTrue(status.contains("\"liveQuotes\":0") && status.contains("\"ready\":false"), status);
      assertTrue(p.killSwitchEngaged());
      assertEquals(0, p.liveQuotes());

      // no reconnect later either
      final long logons = p.d2cSession().logons() + p.clobSession().logons();
      for (int i = 0; i < 2_000; i++) {
        p.driveGateways();
      }
      assertEquals(logons, p.d2cSession().logons() + p.clobSession().logons());
    } finally {
      p.close();
    }
  }
}
