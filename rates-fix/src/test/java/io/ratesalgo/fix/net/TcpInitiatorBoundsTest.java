package io.ratesalgo.fix.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixFramer;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixSessionConfig;
import io.ratesalgo.fix.session.FixSessionHandler;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * The transport's bounds (review finding L5): a venue that stops reading, a connect that is never
 * answered, and a stream that declares a frame larger than the read buffer. Each used to hang or
 * stall the one gateway thread; each now ends the connection and is counted.
 */
class TcpInitiatorBoundsTest {

  private int disconnects;

  private final FixSessionHandler handler =
      new FixSessionHandler() {
        @Override
        public void onApplicationMessage(final FixSession s, final FixParser m, final boolean possDup) {}

        @Override
        public void onLogon(final FixSession s) {}

        @Override
        public void onDisconnect(final FixSession s) {
          disconnects++;
        }

        @Override
        public void onSessionReject(final FixSession s, final FixParser m) {}
      };

  private FixSession attach(final TcpInitiator tcp) {
    final FixSession session =
        new FixSession(new FixSessionConfig("ENGINE", "VENUE", 30, true), new ManualClock(1L), tcp, handler);
    tcp.attach(session);
    return session;
  }

  @Test
  void aVenueThatStopsReadingIsDisconnectedInsteadOfHangingTheSender() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      final TcpInitiator tcp = new TcpInitiator("127.0.0.1", server.getLocalPort(), 1 << 16, 2_000_000_000L, 200);
      attach(tcp);
      tcp.connect();
      try (Socket accepted = server.accept()) {
        // the venue never reads: the socket buffers fill and writes stop making progress
        assertTrue(accepted.isConnected());
        final UnsafeBuffer chunk = new UnsafeBuffer(ByteBuffer.allocateDirect(1 << 15));
        assertTimeoutPreemptively(
            Duration.ofSeconds(60),
            () -> {
              int sends = 0;
              while (tcp.isConnected()) {
                tcp.send(chunk, 0, chunk.capacity());
                assertTrue(++sends < 1_000_000, "the socket buffers never filled");
              }
            });
        assertEquals(1, tcp.writeTimeouts());
        assertFalse(tcp.isConnected());
        assertEquals(1, disconnects, "the session was told");
      }
    }
  }

  @Test
  void aConnectThatIsNotAnsweredFailsAtItsDeadline() {
    // 192.0.2.1 is TEST-NET-1 (RFC 5737): never routed, so the connect is either left unanswered
    // or refused at once by the local stack. Both must surface as a failed connect, quickly.
    // Not loopback, so the initiator must be given TLS settings; the connect fails before any handshake.
    final TcpInitiator tcp =
        new TcpInitiator("192.0.2.1", 9, 1 << 12, 200_000_000L, 10, TestCerts.client("server"));
    attach(tcp);
    assertTimeoutPreemptively(
        Duration.ofSeconds(10), () -> assertThrows(UncheckedIOException.class, tcp::connect));
    assertFalse(tcp.isConnected());
    assertEquals(0, tcp.handshakeTimeouts() + tcp.handshakeFailures(), "the handshake was never reached");
  }

  @Test
  void anAbsurdBodyLengthDoesNotStallTheStream() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      final TcpInitiator tcp = new TcpInitiator("127.0.0.1", server.getLocalPort(), 1 << 16);
      final FixSession session = attach(tcp);
      tcp.connect();
      try (Socket accepted = server.accept()) {
        final OutputStream out = accepted.getOutputStream();
        // a header that declares ten megabytes, then a well-formed heartbeat
        out.write("8=FIX.4.4\u00019=9999999\u000135=0\u0001".getBytes(StandardCharsets.US_ASCII));
        final FixWriter w = new FixWriter(256);
        w.begin(MsgType.HEARTBEAT).putText(49, "VENUE").putText(56, "ENGINE").put(34, 1);
        final UnsafeBuffer msg = new UnsafeBuffer(new byte[512]);
        final int length = w.finish(msg, 0);
        out.write(msg.byteArray(), 0, length);
        out.flush();
        // before the limit the framer waited for the ten megabytes and nothing was ever delivered
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            () -> {
              while (session.stats().received() == 0) {
                tcp.poll();
                Thread.onSpinWait();
              }
            });
        assertEquals(1, session.stats().received());
      }
    }
  }

  @Test
  void anAbsurdBodyLengthIsGarbageBeforeTheBodyArrives() {
    final byte[] text = "8=FIX.4.4\u00019=9999999\u000135=0\u0001".getBytes(StandardCharsets.US_ASCII);
    final UnsafeBuffer b = new UnsafeBuffer(text);
    assertEquals(FixFramer.GARBAGE, FixFramer.frame(b, 0, text.length), "above the default limit");
    final byte[] ok = "8=FIX.4.4\u00019=262144\u000135=0\u0001".getBytes(StandardCharsets.US_ASCII);
    assertEquals(0, FixFramer.frame(new UnsafeBuffer(ok), 0, ok.length), "at the limit: wait for the body");
    final byte[] small = "8=FIX.4.4\u00019=65\u000135=0\u0001".getBytes(StandardCharsets.US_ASCII);
    assertEquals(FixFramer.GARBAGE, FixFramer.frame(new UnsafeBuffer(small), 0, small.length, 64));
    assertEquals(0, FixFramer.frame(new UnsafeBuffer(small), 0, small.length, 65));
    // the digits alone decide: the limit applies while BodyLength is still being read
    final byte[] partial = "8=FIX.4.4\u00019=99999".getBytes(StandardCharsets.US_ASCII);
    assertEquals(FixFramer.GARBAGE, FixFramer.frame(new UnsafeBuffer(partial), 0, partial.length, 1000));
  }
}
