package io.ratesalgo.fix.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.security.KeytoolCertificates.Identity;
import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixSessionConfig;
import io.ratesalgo.fix.session.FixSessionHandler;
import io.ratesalgo.fix.session.SessionState;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.cert.CertificateExpiredException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLException;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * TLS on the engine's own transport, over real loopback sockets against a JDK TLS acceptor: what
 * is accepted, what is refused and why, and that the bounds of the plain transport still hold.
 */
class TcpInitiatorTlsTest {

  private static final long SECOND = 1_000_000_000L;
  private static final String FIX_PASSWORD = "fix-logon-password-1";

  private final List<String> app = new ArrayList<>();
  private int logons;
  private int disconnects;

  private final FixSessionHandler handler =
      new FixSessionHandler() {
        @Override
        public void onApplicationMessage(final FixSession s, final FixParser m, final boolean possDup) {
          app.add(m.getString(FixTags.QUOTE_REQ_ID));
        }

        @Override
        public void onLogon(final FixSession s) {
          logons++;
        }

        @Override
        public void onDisconnect(final FixSession s) {
          disconnects++;
        }
      };

  private FixSession attach(final TcpInitiator tcp) {
    final FixSessionConfig config =
        new FixSessionConfig("ENGINE", "VENUE", 30, true).withCredentials("desk-1", Secret.of(FIX_PASSWORD));
    final FixSession session = new FixSession(config, new ManualClock(1L), tcp, handler);
    tcp.attach(session);
    return session;
  }

  private static TcpInitiator tls(final String host, final TestAcceptor acceptor, final TlsConfig config) {
    return new TcpInitiator(host, acceptor.port(), 1 << 16, 2 * SECOND, 20_000, config);
  }

  private static void pollUntil(final TcpInitiator tcp, final BooleanSupplier condition) {
    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () -> {
          while (!condition.getAsBoolean()) {
            tcp.poll();
            LockSupport.parkNanos(20_000L);
          }
        });
  }

  private static String chain(final Throwable thrown) {
    final StringBuilder text = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      text.append(t).append('\n');
    }
    return text.toString();
  }

  private static boolean caused(final Throwable thrown, final Class<? extends Throwable> type) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }

  @Test
  void handshakeThenLogonAndMessagesBothWays() throws Exception {
    try (TestAcceptor venue = new TestAcceptor(TestCerts.server("server"), TestAcceptor.Mode.FIX);
        TcpInitiator tcp = tls("127.0.0.1", venue, TestCerts.client("server"))) {
      final FixSession session = attach(tcp);
      assertTrue(tcp.isTls());
      tcp.connect();
      pollUntil(tcp, () -> session.state() == SessionState.ACTIVE);
      assertEquals(1, logons);
      assertNull(venue.failure());
      // the Logon and its credentials arrived intact through the TLS records
      final String logon = venue.received.get(0);
      assertTrue(logon.contains("|35=A|49=ENGINE|56=VENUE|34=1|"), logon);
      assertTrue(logon.contains("|553=desk-1|554=" + FIX_PASSWORD + "|"), "credentials reach the venue");

      venue.send(MsgType.QUOTE_REQUEST, w -> w.putText(FixTags.QUOTE_REQ_ID, "rfq-1"));
      pollUntil(tcp, () -> app.size() == 1);
      assertEquals(List.of("rfq-1"), app);
      session.begin(MsgType.QUOTE).putText(FixTags.QUOTE_REQ_ID, "rfq-1").put(FixTags.QUOTE_ID, 7);
      session.send();
      pollUntil(tcp, () -> venue.receivedCount() == 2);
      assertTrue(venue.received.get(1).contains("|35=S|"), venue.received.get(1));

      // more than the plain buffer in one burst of full-size records: decrypting makes room by
      // framing what it already has
      venue.sendBatch(MsgType.QUOTE_REQUEST, w -> w.putText(FixTags.QUOTE_REQ_ID, "burst"), 3000);
      pollUntil(tcp, () -> app.size() == 3001);
      assertEquals(0, session.stats().sequenceErrors());

      // the venue ends the TLS session with close_notify: a disconnect, not an error
      venue.closeConnection();
      pollUntil(tcp, () -> !tcp.isConnected());
      assertEquals(1, disconnects);
      assertEquals(0, tcp.tlsErrors());
      assertEquals(0, tcp.handshakeFailures());
      assertTrue(tcp.bytesRead() > 0);
    }
  }

  @Test
  void aServerCertificateOutsideTheTrustStoreIsRefused() throws Exception {
    try (TestAcceptor venue = new TestAcceptor(TestCerts.server("server"), TestAcceptor.Mode.FIX);
        TcpInitiator tcp = tls("127.0.0.1", venue, TestCerts.client("someone-else"))) {
      final FixSession session = attach(tcp);
      final UncheckedIOException refused = assertThrows(UncheckedIOException.class, tcp::connect);
      assertTrue(caused(refused, SSLException.class), chain(refused));
      assertEquals(1, tcp.handshakeFailures());
      assertEquals(0, tcp.handshakeTimeouts());
      assertFalse(tcp.isConnected());
      assertEquals(SessionState.DISCONNECTED, session.state());
      assertEquals(0, session.stats().sent(), "no Logon, and so no password, left the process");
      final String text = chain(refused);
      assertFalse(text.contains(TestCerts.STORE_PASSWORD), text);
      assertFalse(text.contains(FIX_PASSWORD), text);
      assertTrue(venue.awaitEnd());
      assertEquals(0, venue.receivedCount());
    }
  }

  @Test
  void anExpiredServerCertificateIsRefusedEvenWhenItIsInTheTrustStore() throws Exception {
    final Identity old = TestCerts.expired();
    final TlsConfig server = TlsConfig.builder().keyStore(old.keyStore(), TestCerts.password()).build();
    final TlsConfig client = TlsConfig.builder().trustStore(old.trustStore(), TestCerts.password()).build();
    try (TestAcceptor venue = new TestAcceptor(server, TestAcceptor.Mode.FIX);
        TcpInitiator tcp = tls("127.0.0.1", venue, client)) {
      final FixSession session = attach(tcp);
      final UncheckedIOException refused = assertThrows(UncheckedIOException.class, tcp::connect);
      assertTrue(caused(refused, CertificateExpiredException.class), chain(refused));
      assertEquals(1, tcp.handshakeFailures());
      assertFalse(tcp.isConnected());
      assertEquals(0, session.stats().sent());
    }
  }

  @Test
  void aCertificateForAnotherNameIsRefusedUnlessVerificationIsOff() throws Exception {
    // the test certificate names localhost and 127.0.0.1; 127.0.0.2 is the same machine by another name
    final InetAddress other = InetAddress.getByName("127.0.0.2");
    try (TestAcceptor venue = new TestAcceptor(TestCerts.server("server"), TestAcceptor.Mode.FIX, other);
        TcpInitiator tcp = tls("127.0.0.2", venue, TestCerts.client("server"))) {
      attach(tcp);
      final UncheckedIOException refused = assertThrows(UncheckedIOException.class, tcp::connect);
      assertTrue(chain(refused).contains("127.0.0.2"), chain(refused));
      assertEquals(1, tcp.handshakeFailures());
      assertFalse(tcp.isConnected());
    }
    final TlsConfig unverified =
        TlsConfig.builder()
            .trustStore(TestCerts.identity("server").trustStore(), TestCerts.password())
            .verifyHostname(false)
            .build();
    try (TestAcceptor venue = new TestAcceptor(TestCerts.server("server"), TestAcceptor.Mode.FIX, other);
        TcpInitiator tcp = tls("127.0.0.2", venue, unverified)) {
      final FixSession session = attach(tcp);
      tcp.connect();
      pollUntil(tcp, () -> session.state() == SessionState.ACTIVE);
      assertEquals(0, tcp.handshakeFailures());
    }
  }

  @Test
  void mutualTlsNeedsAClientCertificateTheServerTrusts() throws Exception {
    final TlsConfig server =
        TlsConfig.builder()
            .keyStore(TestCerts.identity("server").keyStore(), TestCerts.password())
            .trustStore(TestCerts.identity("client").trustStore(), TestCerts.password())
            .requireClientCertificate(true)
            .build();
    // no client certificate: the server ends the connection and the Logon is never answered
    try (TestAcceptor venue = new TestAcceptor(server, TestAcceptor.Mode.FIX);
        TcpInitiator tcp = tls("127.0.0.1", venue, TestCerts.client("server"))) {
      final FixSession session = attach(tcp);
      try {
        tcp.connect();
        pollUntil(tcp, () -> !tcp.isConnected());
      } catch (final UncheckedIOException atHandshake) {
        assertTrue(caused(atHandshake, SSLException.class), chain(atHandshake));
      }
      assertFalse(tcp.isConnected());
      assertEquals(0, logons);
      assertTrue(session.state() != SessionState.ACTIVE);
      assertTrue(venue.awaitEnd());
      assertNotNull(venue.failure(), "the server refused the handshake");
      assertEquals(0, venue.receivedCount(), "the Logon was not read by the venue");
    }
    final TlsConfig withCertificate =
        TlsConfig.builder()
            .trustStore(TestCerts.identity("server").trustStore(), TestCerts.password())
            .keyStore(TestCerts.identity("client").keyStore(), TestCerts.password())
            .build();
    try (TestAcceptor venue = new TestAcceptor(server, TestAcceptor.Mode.FIX);
        TcpInitiator tcp = tls("127.0.0.1", venue, withCertificate)) {
      final FixSession session = attach(tcp);
      tcp.connect();
      pollUntil(tcp, () -> session.state() == SessionState.ACTIVE);
      assertNull(venue.failure());
      assertEquals(1, logons);
    }
  }

  @Test
  void aPeerThatOnlySpeaksTls12IsRefusedByDefault() throws Exception {
    final TlsConfig oldServer =
        TlsConfig.builder()
            .keyStore(TestCerts.identity("server").keyStore(), TestCerts.password())
            .protocols(List.of("TLSv1.2"))
            .build();
    try (TestAcceptor venue = new TestAcceptor(oldServer, TestAcceptor.Mode.FIX);
        TcpInitiator tcp = tls("127.0.0.1", venue, TestCerts.client("server"))) {
      attach(tcp);
      final UncheckedIOException refused = assertThrows(UncheckedIOException.class, tcp::connect);
      assertTrue(caused(refused, SSLException.class), chain(refused));
      assertEquals(1, tcp.handshakeFailures());
      assertFalse(tcp.isConnected());
    }
    // the same server is reachable once the client is told to allow 1.2: the refusal was the protocol
    final TlsConfig allows12 =
        TlsConfig.builder()
            .trustStore(TestCerts.identity("server").trustStore(), TestCerts.password())
            .protocols(List.of("TLSv1.3", "TLSv1.2"))
            .build();
    try (TestAcceptor venue = new TestAcceptor(oldServer, TestAcceptor.Mode.FIX);
        TcpInitiator tcp = tls("127.0.0.1", venue, allows12)) {
      final FixSession session = attach(tcp);
      tcp.connect();
      pollUntil(tcp, () -> session.state() == SessionState.ACTIVE);
    }
  }

  @Test
  void aSilentPeerEndsTheHandshakeAtItsDeadline() throws Exception {
    final TlsConfig impatient =
        TlsConfig.builder()
            .trustStore(TestCerts.identity("server").trustStore(), TestCerts.password())
            .handshakeTimeoutNanos(300_000_000L)
            .build();
    try (TestAcceptor venue = new TestAcceptor(null, TestAcceptor.Mode.SILENT);
        TcpInitiator tcp = tls("127.0.0.1", venue, impatient)) {
      attach(tcp);
      final long start = System.nanoTime();
      final UncheckedIOException late =
          assertTimeoutPreemptively(
              Duration.ofSeconds(10), () -> assertThrows(UncheckedIOException.class, tcp::connect));
      final long took = System.nanoTime() - start;
      assertTrue(took >= 300_000_000L, "not before the deadline: " + took);
      assertTrue(took < 5 * SECOND, "soon after the deadline: " + took);
      assertTrue(late.getMessage().contains("no TLS handshake"), late.getMessage());
      assertEquals(1, tcp.handshakeTimeouts());
      assertEquals(0, tcp.handshakeFailures());
      assertEquals(0, tcp.connectTimeouts());
      assertFalse(tcp.isConnected());
    }
  }

  @Test
  void aVenueThatStopsReadingIsDisconnectedUnderTlsToo() throws Exception {
    try (TestAcceptor venue = new TestAcceptor(TestCerts.server("server"), TestAcceptor.Mode.DEAF)) {
      final TcpInitiator tcp =
          new TcpInitiator("127.0.0.1", venue.port(), 1 << 16, 2 * SECOND, 200, TestCerts.client("server"));
      attach(tcp);
      tcp.connect();
      assertTrue(venue.awaitReady());
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
      tcp.send(chunk, 0, 16); // a send on a closed connection is dropped, not an error
      assertEquals(1, tcp.writeTimeouts());
    }
  }

  @Test
  void abortFromAnotherThreadClosesATlsConnection() throws Exception {
    try (TestAcceptor venue = new TestAcceptor(TestCerts.server("server"), TestAcceptor.Mode.FIX);
        TcpInitiator tcp = tls("127.0.0.1", venue, TestCerts.client("server"))) {
      final FixSession session = attach(tcp);
      tcp.connect();
      pollUntil(tcp, () -> session.state() == SessionState.ACTIVE);
      final Thread supervisor = new Thread(tcp::abort, "supervisor");
      supervisor.start();
      supervisor.join();
      assertFalse(tcp.isConnected());
      assertEquals(0, disconnects, "the owner has not polled yet");
      tcp.poll();
      assertEquals(1, disconnects);
      assertEquals(SessionState.DISCONNECTED, session.state());
      assertTrue(venue.awaitEnd());
    }
  }

  @Test
  void plainFixIsForLoopbackOnly() {
    final IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> new TcpInitiator("192.0.2.1", 9, 1 << 12));
    assertTrue(refused.getMessage().contains("refusing a FIX connection to 192.0.2.1 without TLS"));
    assertThrows(
        IllegalArgumentException.class, () -> new TcpInitiator("venue.example.com", 9, 1 << 12, SECOND, 10, null));
    assertTrue(TcpInitiator.isLoopback("127.0.0.1"));
    assertTrue(TcpInitiator.isLoopback("127.8.9.10"));
    assertTrue(TcpInitiator.isLoopback("::1"));
    assertTrue(TcpInitiator.isLoopback("localhost"));
    assertTrue(TcpInitiator.isLoopback("LOCALHOST"));
    assertFalse(TcpInitiator.isLoopback("10.0.0.1"));
    assertFalse(TcpInitiator.isLoopback("0.0.0.0"));
    assertFalse(TcpInitiator.isLoopback("localhost.example.com"));
    assertFalse(TcpInitiator.isLoopback(""));
    assertFalse(TcpInitiator.isLoopback(null));
    final TcpInitiator plain = new TcpInitiator("localhost", 9, 1 << 12);
    assertFalse(plain.isTls());
    // with TLS any host may be named; nothing is dialled until connect
    assertTrue(new TcpInitiator("venue.example.com", 9, 1 << 12, SECOND, 10, TestCerts.client("server")).isTls());
  }
}
