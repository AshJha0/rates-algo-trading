package io.ratesalgo.app.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.gateway.InstrumentMap;
import io.ratesalgo.fix.session.SessionState;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.junit.jupiter.api.Test;

/**
 * The bounded logon retry of {@link VenueSession} (DR-INT-09), against a venue on a real socket that
 * closes the first connection without answering our Logon (as a QuickFIX/J acceptor that still
 * holds the previous session does) and accepts the second.
 */
class VenueSessionReconnectTest {

  private static final int GATEWAY = 7;

  /** A venue that refuses the first {@code refusals} connections, then answers the Logon and stays up. */
  private static final class FakeVenue implements AutoCloseable {
    private final ServerSocket server;
    private final Thread thread;
    private final AtomicInteger connections = new AtomicInteger();
    private final List<Socket> open = Collections.synchronizedList(new ArrayList<>());

    FakeVenue(final int refusals) throws IOException {
      server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
      thread = new Thread(() -> serve(refusals), "fake-venue");
      thread.setDaemon(true);
      thread.start();
    }

    int port() {
      return server.getLocalPort();
    }

    int connections() {
      return connections.get();
    }

    private void serve(final int refusals) {
      try {
        while (!server.isClosed()) {
          final Socket s = server.accept();
          final int n = connections.incrementAndGet();
          final InputStream in = s.getInputStream();
          final byte[] buf = new byte[4096];
          in.read(buf); // our Logon
          if (n <= refusals) {
            s.close();
            continue;
          }
          final FixWriter w = new FixWriter(1024);
          w.begin(MsgType.LOGON)
              .putText(FixTags.SENDER_COMP_ID, "VENUE")
              .putText(FixTags.TARGET_COMP_ID, "RALGO")
              .put(FixTags.MSG_SEQ_NUM, 1)
              .putTimestamp(FixTags.SENDING_TIME, 1_793_629_800_000_000_000L)
              .put(FixTags.ENCRYPT_METHOD, 0)
              .put(FixTags.HEART_BT_INT, 30)
              .putBool(FixTags.RESET_SEQ_NUM_FLAG, true);
          final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocate(1024));
          final int length = w.finish(out, 0);
          final OutputStream os = s.getOutputStream();
          os.write(out.byteArray(), 0, length);
          os.flush();
          open.add(s);
        }
      } catch (final IOException closed) {
        // the test closed the server
      }
    }

    /** Drops every connection the venue holds, as a venue or a network that fails does. */
    void drop() throws IOException {
      synchronized (open) {
        for (final Socket s : open) {
          s.close();
        }
        open.clear();
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
      for (final Socket s : open) {
        s.close();
      }
    }
  }

  private final ManualClock clock = new ManualClock(1_793_629_800_000_000_000L);
  private final List<int[]> statuses = Collections.synchronizedList(new ArrayList<>());

  private VenueSession session(final int port, final ReconnectPolicy policy) {
    return session(port, policy, VenueSession.Link.SIMULATOR);
  }

  private VenueSession session(final int port, final ReconnectPolicy policy, final VenueSession.Link link) {
    return new VenueSession(
        101,
        GATEWAY,
        new String[] {"RALGO", "VENUE"},
        "127.0.0.1",
        port,
        new InstrumentMap(),
        (b, o, l) -> {
          if (RingBufferContract.templateId(b, o) == ControlMessages.GATEWAY_STATUS_TEMPLATE_ID) {
            statuses.add(new int[] {ControlMessages.gatewayStatus(b, o), ControlMessages.gatewayStatusAttempt(b, o)});
          }
          return true;
        },
        clock,
        new RingBuffer[0],
        policy,
        VenueSession.WithdrawPolicy.DEFAULT,
        link);
  }

  @Test
  void anActiveSessionThatIsDroppedReconnectsAfterTheBackoff() throws IOException {
    try (FakeVenue venue = new FakeVenue(0)) {
      final VenueSession s = session(venue.port(), ReconnectPolicy.DEFAULT);
      s.connect();
      assertTrue(drive(s, s::isActive), "logged on: " + diagnostics(s, venue));
      assertEquals(0, s.drops());
      venue.drop();
      assertTrue(drive(s, () -> s.drops() == 1), "saw the drop: " + diagnostics(s, venue));
      assertTrue(s.reconnectPending(), "a reconnect is scheduled, not made at once");
      assertEquals(1, venue.connections(), "nothing before the backoff has passed");
      assertTrue(drive(s, () -> s.logons() == 2), "logged on again: " + diagnostics(s, venue));
      assertEquals(2, venue.connections());
      assertEquals(1, s.reconnectAttempts());
      assertEquals(
          List.of((int) ControlMessages.STATUS_CONNECTED, (int) ControlMessages.STATUS_DISCONNECTED,
              (int) ControlMessages.STATUS_RECONNECTING, (int) ControlMessages.STATUS_CONNECTED),
          statusCodes());
      assertFalse(s.isReconciling(), "a venue that holds no orders for us has nothing to reconcile");

      // a second drop is retried from the first backoff again, not from where the last one ended
      venue.drop();
      assertTrue(drive(s, () -> s.logons() == 3), "logged on a third time: " + diagnostics(s, venue));
      assertEquals(2, s.drops());
      assertEquals(0, s.reconnectGiveUps());
      s.disconnect();
    }
  }

  @Test
  void aHaltedOrDeliberatelyDisconnectedSessionDoesNotReconnect() throws IOException {
    try (FakeVenue venue = new FakeVenue(0)) {
      final VenueSession halted = session(venue.port(), ReconnectPolicy.DEFAULT);
      halted.connect();
      assertTrue(drive(halted, halted::isActive), diagnostics(halted, venue));
      halted.halt(false);
      venue.drop();
      assertTrue(drive(halted, () -> halted.disconnects() == 1), diagnostics(halted, venue));
      assertFalse(halted.reconnectPending(), "a halt is final: no way back short of a restart");
      assertEquals(0, halted.drops());
      for (int i = 0; i < 50; i++) {
        clock.advance(100_000_000L);
        halted.doWork();
      }
      assertEquals(1, venue.connections());
      halted.connect();
      assertEquals(1, venue.connections(), "connect() on a halted session does nothing");

      final VenueSession none = session(venue.port(), ReconnectPolicy.NONE);
      none.connect();
      assertTrue(drive(none, none::isActive), diagnostics(none, venue));
      venue.drop();
      assertTrue(drive(none, () -> none.disconnects() == 1), diagnostics(none, venue));
      assertFalse(none.reconnectPending(), "no retries means none after a drop either");
    }
  }

  @Test
  void aReturningSessionReconcilesAndAVenueThatNeverAnswersIsDroppedAndTriedAgain() throws IOException {
    try (FakeVenue venue = new FakeVenue(0)) {
      final VenueSession s =
          session(venue.port(), ReconnectPolicy.DEFAULT, VenueSession.Link.SIMULATOR.withReconcileOrders(true));
      s.recovered(); // the process restarted: this Logon is a return
      s.connect();
      assertTrue(drive(s, s::isActive), diagnostics(s, venue));
      assertTrue(s.isReconciling());
      assertEquals(List.of((int) ControlMessages.STATUS_RECONCILING), statusCodes(),
          "the loop is told the venue is being reconciled, not that it is up");
      assertEquals(3, s.session().nextOutSeq(), "the Logon and one OrderMassStatusRequest");

      // the fake venue never answers: two more requests, then the connection is dropped
      clock.advance(VenueSession.RECONCILE_TIMEOUT_NANOS);
      s.doWork();
      assertEquals(4, s.session().nextOutSeq());
      clock.advance(VenueSession.RECONCILE_TIMEOUT_NANOS);
      s.doWork();
      assertEquals(5, s.session().nextOutSeq());
      assertTrue(s.isReconciling() && s.isActive());
      clock.advance(VenueSession.RECONCILE_TIMEOUT_NANOS);
      s.doWork();
      assertFalse(s.isReconciling());
      assertFalse(s.isActive());
      assertEquals(0, s.reconciliations());
      assertEquals(1, s.drops());
      assertTrue(s.reconnectPending(), "the reconnect brings another attempt at reconciling");
      assertEquals(
          List.of((int) ControlMessages.STATUS_RECONCILING, (int) ControlMessages.STATUS_DISCONNECTED), statusCodes());
      assertTrue(drive(s, () -> s.logons() == 2), diagnostics(s, venue));
      assertTrue(s.isReconciling());
      s.disconnect();
      assertFalse(s.isReconciling());
    }
  }

  @Test
  void aLinkSaysWhatItNeeds() {
    assertThrows(IllegalArgumentException.class,
        () -> new VenueSession.Link(null, null, null, false, null, false));
    assertThrows(IllegalArgumentException.class,
        () -> new VenueSession.Link(null, "user", null, true, null, false));
    final VenueSession.Link link = VenueSession.Link.SIMULATOR.withReconcileOrders(true);
    assertTrue(link.reconcileOrders() && link.resetOnLogon());
    assertFalse(VenueSession.InboundDrained.NEVER.drained());
  }

  /**
   * Runs the session's duty cycle until the condition holds or 10 s of wall-clock time pass,
   * advancing the session's clock by the wall-clock time each turn took. The venue runs on its own
   * thread in real time, so the session's logon timeout (30 s) and backoff must run on the same
   * time base: advancing a fixed 10 ms a turn made 30 simulated seconds pass in about 20 ms, and a
   * venue thread not scheduled within that lost every attempt on the 2-vCPU Windows runner
   * (DR-INT-09).
   */
  private boolean drive(final VenueSession s, final BooleanSupplier condition) {
    final long start = System.nanoTime();
    final long deadline = start + 10_000_000_000L;
    long last = start;
    while (!condition.getAsBoolean()) {
      final long now = System.nanoTime();
      if (now > deadline) {
        return false;
      }
      clock.advance(now - last);
      last = now;
      s.doWork();
      Thread.onSpinWait();
    }
    return true;
  }

  private String diagnostics(final VenueSession s, final FakeVenue venue) {
    return "state " + s.state() + ", statuses " + statusCodes() + ", venue connections "
        + (venue == null ? "n/a" : String.valueOf(venue.connections())) + ", attempts " + s.reconnectAttempts()
        + ", disconnects " + s.disconnects() + ", logons " + s.logons() + ", give-ups " + s.reconnectGiveUps();
  }

  private List<Integer> statusCodes() {
    final List<Integer> codes = new ArrayList<>();
    synchronized (statuses) {
      for (final int[] st : statuses) {
        codes.add(st[0]);
      }
    }
    return codes;
  }

  @Test
  void aLogonRefusedByTheVenueIsRetriedAndTheSecondIsAccepted() throws IOException {
    try (FakeVenue venue = new FakeVenue(1)) {
      final VenueSession s = session(venue.port(), ReconnectPolicy.DEFAULT);
      s.connect();
      assertTrue(drive(s, s::isActive), "logged on after a retry: " + diagnostics(s, venue));
      assertEquals(SessionState.ACTIVE, s.state());
      assertEquals(2, venue.connections(), diagnostics(s, venue));
      assertEquals(1, s.reconnectAttempts(), diagnostics(s, venue));
      assertEquals(1, s.logons());
      assertEquals(0, s.reconnectGiveUps());
      assertFalse(s.reconnectPending());
      // journaled in order: the refused attempt's disconnect, the retry with its attempt number, the logon
      assertEquals(
          List.of((int) ControlMessages.STATUS_DISCONNECTED, (int) ControlMessages.STATUS_RECONNECTING,
              (int) ControlMessages.STATUS_CONNECTED),
          statusCodes());
      assertEquals(1, statuses.get(1)[1]);
      s.disconnect();
    }
  }

  @Test
  void retriesAreBoundedAndBackOff() throws IOException {
    final ReconnectPolicy policy = new ReconnectPolicy(2, 100_000_000L, 150_000_000L);
    assertEquals(100_000_000L, policy.backoffNanos(1));
    assertEquals(150_000_000L, policy.backoffNanos(2));
    assertEquals(150_000_000L, policy.backoffNanos(9));
    assertEquals(400_000_000L, new ReconnectPolicy(5, 100_000_000L, 2_000_000_000L).backoffNanos(3));
    try (FakeVenue venue = new FakeVenue(Integer.MAX_VALUE)) {
      final VenueSession s = session(venue.port(), policy);
      s.connect();
      assertTrue(drive(s, () -> s.reconnectGiveUps() == 1), "gave up: " + diagnostics(s, venue));
      assertEquals(3, venue.connections(), "the first attempt and two retries: " + diagnostics(s, venue));
      assertEquals(2, s.reconnectAttempts());
      assertFalse(s.isActive());
      assertFalse(s.reconnectPending());
      final int seen = venue.connections();
      for (int i = 0; i < 50; i++) {
        clock.advance(100_000_000L);
        s.doWork();
      }
      assertEquals(seen, venue.connections(), "no attempt after giving up");
    }
  }

  @Test
  void anExplicitDisconnectIsNotRetriedAndNonePolicyKeepsTheOldBehaviour() throws IOException {
    try (FakeVenue venue = new FakeVenue(0)) {
      final VenueSession s = session(venue.port(), ReconnectPolicy.DEFAULT);
      s.connect();
      assertTrue(drive(s, s::isActive));
      s.disconnect();
      for (int i = 0; i < 50; i++) {
        clock.advance(100_000_000L);
        s.doWork();
      }
      assertEquals(1, venue.connections());
      assertEquals(0, s.reconnectAttempts());
    }
    final int closedPort;
    try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = probe.getLocalPort();
    }
    assertThrows(java.io.UncheckedIOException.class, () -> session(closedPort, ReconnectPolicy.NONE).connect());
    final VenueSession retrying = session(closedPort, new ReconnectPolicy(1, 10_000_000L, 10_000_000L));
    retrying.connect();
    assertTrue(retrying.reconnectPending(), "a refused TCP connect is retried");
    assertTrue(drive(retrying, () -> retrying.reconnectGiveUps() == 1));
    assertThrows(IllegalArgumentException.class, () -> new ReconnectPolicy(-1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new ReconnectPolicy(1, 2, 1));
  }
}
