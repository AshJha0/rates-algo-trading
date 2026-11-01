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
        policy);
  }

  /** Runs the session's duty cycle, advancing the clock 10 ms a turn, until the condition holds or 10 s pass. */
  private boolean drive(final VenueSession s, final BooleanSupplier condition) {
    final long deadline = System.nanoTime() + 10_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        return false;
      }
      clock.advance(10_000_000L);
      s.doWork();
      Thread.onSpinWait();
    }
    return true;
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
      assertTrue(drive(s, s::isActive), "logged on after a retry: state " + s.state() + ", " + statusCodes());
      assertEquals(SessionState.ACTIVE, s.state());
      assertEquals(2, venue.connections());
      assertEquals(1, s.reconnectAttempts());
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
      assertTrue(drive(s, () -> s.reconnectGiveUps() == 1), "gave up: " + statusCodes());
      assertEquals(3, venue.connections(), "the first attempt and two retries");
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
