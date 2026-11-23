package io.ratesalgo.fix.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixSessionConfig;
import io.ratesalgo.fix.session.SessionState;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Bytes allocated per message by the transport in steady state, measured as AllocationTest does
 * (the thread's allocated-bytes counter after warm-up), over a real loopback socket with a venue
 * end on another thread. Plain TCP must allocate nothing. Under TLS our own code allocates
 * nothing either, but the JDK's SSLEngine does, inside wrap and unwrap; the test prints the
 * figure and holds it under a ceiling so that a regression (a buffer allocated per message, say)
 * fails the build. The figures in TcpInitiator's class comment come from this test.
 */
class TlsAllocationTest {

  private static final int WARM_UP = 30_000;
  private static final int MEASURED = 50_000;

  /** Ceiling on what the JDK engine may allocate per message; the measured figures are below it. */
  private static final long TLS_CEILING_BYTES = 2_048;

  private static final byte[] ISIN = "US91282CZH77".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private static void fill(final FixWriter w) {
    w.put(FixTags.ORDER_ID, 1)
        .put(FixTags.CL_ORD_ID, 77)
        .put(FixTags.EXEC_ID, 123_456)
        .putChar(FixTags.EXEC_TYPE, (byte) 'F')
        .putChar(FixTags.ORD_STATUS, (byte) '1')
        .putAscii(FixTags.SECURITY_ID, ISIN)
        .putChar(FixTags.SIDE, (byte) '2')
        .put(FixTags.ORDER_QTY, 1_000_000_000)
        .put(FixTags.LAST_QTY, 1_000_000)
        .putDecimal(FixTags.LAST_PX, 99_500_000_000L, 9);
  }

  private long delivered;

  @Test
  void plainTcpAllocatesNothingPerMessage() throws Exception {
    final long[] perMessage = measure(null, null);
    System.out.println("plain TCP: " + perMessage[0] + " bytes/message sent, " + perMessage[1] + " received");
    assertEquals(0, perMessage[0], "bytes allocated per message sent");
    assertEquals(0, perMessage[1], "bytes allocated per message received");
  }

  @Test
  void tlsAllocationPerMessageIsMeasuredAndBounded() throws Exception {
    final long[] perMessage = measure(TestCerts.server("server"), TestCerts.client("server"));
    System.out.println(
        "TLS 1.3 (JDK SSLEngine): " + perMessage[0] + " bytes/message sent, " + perMessage[1] + " received");
    assertTrue(perMessage[0] <= TLS_CEILING_BYTES, "bytes allocated per message sent: " + perMessage[0]);
    assertTrue(perMessage[1] <= TLS_CEILING_BYTES, "bytes allocated per message received: " + perMessage[1]);
  }

  /** Returns bytes allocated by this thread per message sent and per message received. */
  private long[] measure(final TlsConfig server, final TlsConfig client) throws Exception {
    try (TestAcceptor venue = new TestAcceptor(server, TestAcceptor.Mode.FIX);
        TcpInitiator tcp =
            new TcpInitiator(
                "127.0.0.1",
                venue.port(),
                1 << 16,
                TcpInitiator.DEFAULT_CONNECT_TIMEOUT_NANOS,
                TcpInitiator.DEFAULT_MAX_WRITE_SPINS,
                client)) {
      final FixSession session =
          new FixSession(
              new FixSessionConfig("ENGINE", "VENUE", 30, true), new ManualClock(1L), tcp, (s, m, d) -> delivered++);
      tcp.attach(session);
      venue.quiet();
      tcp.connect();
      await(tcp, () -> session.state() == SessionState.ACTIVE);

      final com.sun.management.ThreadMXBean mx =
          (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      final long thread = Thread.currentThread().threadId();
      mx.getThreadAllocatedBytes(thread);

      send(session, tcp, WARM_UP);
      long before = mx.getThreadAllocatedBytes(thread);
      send(session, tcp, MEASURED);
      final long sent = mx.getThreadAllocatedBytes(thread) - before;

      receive(venue, tcp, WARM_UP);
      before = mx.getThreadAllocatedBytes(thread);
      final long target = delivered + MEASURED;
      venue.stream(MsgType.EXECUTION_REPORT, TlsAllocationTest::fill, MEASURED);
      final long deadline = System.nanoTime() + 120_000_000_000L;
      while (delivered < target) {
        tcp.poll();
        assertTrue(System.nanoTime() < deadline, "the venue's messages did not arrive");
      }
      final long received = mx.getThreadAllocatedBytes(thread) - before;

      assertEquals(0, session.stats().sequenceErrors());
      assertEquals(0, session.stats().garbled());
      assertTrue(tcp.isConnected());
      await(tcp, () -> venue.receivedCount() == 1 + WARM_UP + MEASURED);
      return new long[] {sent / MEASURED, received / MEASURED};
    }
  }

  private static void send(final FixSession session, final TcpInitiator tcp, final int messages) {
    for (int i = 0; i < messages; i++) {
      fill(session.begin(MsgType.EXECUTION_REPORT));
      session.send();
    }
    assertTrue(tcp.isConnected(), "the venue kept reading");
  }

  private void receive(final TestAcceptor venue, final TcpInitiator tcp, final int messages) {
    final long target = delivered + messages;
    venue.stream(MsgType.EXECUTION_REPORT, TlsAllocationTest::fill, messages);
    await(tcp, () -> delivered >= target);
  }

  private static void await(final TcpInitiator tcp, final java.util.function.BooleanSupplier condition) {
    assertTimeoutPreemptively(
        Duration.ofMinutes(2),
        () -> {
          while (!condition.getAsBoolean()) {
            tcp.poll();
          }
        });
  }
}
