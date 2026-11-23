package io.ratesalgo.venues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.security.KeytoolCertificates;
import io.ratesalgo.common.security.KeytoolCertificates.Identity;
import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.gateway.InstrumentMap;
import io.ratesalgo.fix.gateway.VenueGateway;
import io.ratesalgo.fix.order.OrderStateTracker;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixSessionHandler;
import io.ratesalgo.fix.session.SessionState;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderEncoder;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.AcceptorOptions;
import io.ratesalgo.venues.sim.EngineSession;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.Px;
import io.ratesalgo.venues.sim.VenueHarness;
import io.ratesalgo.venues.sim.VenueSimulator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The simulators' listening side against our own transport and session, over real loopback
 * sockets: TLS, logon credentials, and where a plain acceptor may listen.
 */
class VenueSecurityTest {

  private static final String STORE_PASSWORD = "venue-store-pass-for-tests";
  private static final String LOGON_PASSWORD = "desk-logon-pass-7";
  private static final Secret STORE = Secret.of(STORE_PASSWORD);

  @TempDir static Path certs;
  private static Identity venue;
  private static Identity stranger;
  private static Identity client;

  private final List<UnsafeBuffer> sbe = new ArrayList<>();
  private final UnsafeBuffer out = new UnsafeBuffer(new byte[256]);

  @BeforeAll
  static void certificates() {
    venue = KeytoolCertificates.localhost(certs, "venue", STORE, 30);
    stranger = KeytoolCertificates.localhost(certs, "stranger", STORE, 30);
    client = KeytoolCertificates.localhost(certs, "client", STORE, 30);
  }

  private static TlsConfig serverTls() {
    return TlsConfig.builder().keyStore(venue.keyStore(), STORE).build();
  }

  private static TlsConfig clientTls() {
    return TlsConfig.builder().trustStore(venue.trustStore(), STORE).build();
  }

  private static ClobSimulator clob(final ManualClock clock) {
    return (ClobSimulator) Venues.create("clob", clock, 3, new InProcessFeedChannel());
  }

  private VenueGateway gateway(final ManualClock clock) {
    final InstrumentMap map = new InstrumentMap();
    for (final Instrument i : Venues.treasuries()) {
      map.register(i.instrumentId(), i.securityId());
    }
    return new VenueGateway(102, map, (b, o, l) -> {
      final byte[] copy = new byte[l];
      b.getBytes(o, copy);
      sbe.add(new UnsafeBuffer(copy));
      return true;
    }, clock, new OrderStateTracker(256, 1024));
  }

  private void order(final VenueGateway g, final long clOrdId, final long price, final long qty) {
    final NewOrderEncoder e = new NewOrderEncoder();
    e.wrapAndApplyHeader(out, 0, new MessageHeaderEncoder()).venueId(102).clOrdId(clOrdId).instrumentId(1007)
        .side(Side.Buy).ordType(OrdType.Limit).timeInForce(TimeInForce.Day).quantity(qty * 100);
    e.price().mantissa(price);
    assertTrue(g.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength()));
  }

  private long fillsBooked() {
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    long fills = 0;
    for (final UnsafeBuffer b : sbe) {
      header.wrap(b, 0);
      if (header.templateId() == ExecutionReportDecoder.TEMPLATE_ID) {
        final ExecutionReportDecoder d = new ExecutionReportDecoder();
        d.wrapAndApplyHeader(b, 0, header);
        if (d.lastQuantity() > 0) {
          fills++;
        }
      }
    }
    return fills;
  }

  /** What a refused logon looks like from the engine side. */
  private static final class Refusal implements FixSessionHandler {
    final List<String> logoutTexts = new ArrayList<>();
    final List<String> raw = Collections.synchronizedList(new ArrayList<>());
    int refused;
    int logons;
    int disconnects;

    @Override
    public void onApplicationMessage(final FixSession s, final FixParser m, final boolean possDup) {}

    @Override
    public void onLogon(final FixSession s) {
      logons++;
    }

    @Override
    public void onLogout(final FixSession s, final FixParser m, final boolean logonRefused) {
      logoutTexts.add(m.getString(FixTags.TEXT));
      if (logonRefused) {
        refused++;
      }
    }

    @Override
    public void onDisconnect(final FixSession s) {
      disconnects++;
    }
  }

  private static void expectRefusal(final VenueSimulator sim, final EngineSession e, final Refusal seen) {
    e.transport().filter((b, o, l) -> {
      final FixParser p = new FixParser();
      p.wrap(b, o, l);
      seen.raw.add(p.toString());
      return true;
    });
    e.transport().connect();
    e.pollUntil(() -> !e.transport().isConnected(), 10_000);
    assertEquals(0, seen.logons);
    assertEquals(1, seen.refused, "the Logout answered our Logon");
    assertEquals(1, seen.disconnects, "and the socket closed");
    assertEquals(List.of(VenueSimulator.LOGON_REFUSED_TEXT), seen.logoutTexts);
    assertEquals(1, e.session().stats().logonsRefused());
    assertTrue(e.session().state() != SessionState.ACTIVE);
    assertTrue(sim.loggedOn().isEmpty());
    // the venue said only that it refused: neither the password we sent nor the one it wanted
    final String everything = String.join("\n", seen.raw) + String.join("\n", sim.eventLog());
    assertTrue(everything.contains("|35=5|"), everything);
    assertFalse(everything.contains(LOGON_PASSWORD), everything);
    assertFalse(everything.contains("not-the-password"), everything);
  }

  @Test
  void anOrderIsFilledOverTlsWithCredentials() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final ClobSimulator clob = clob(clock);
    final VenueGateway g = gateway(clock);
    final AcceptorOptions options = new AcceptorOptions(serverTls(), "desk-1", Secret.of(LOGON_PASSWORD), null);
    try (VenueHarness h = new VenueHarness(clob, clock, options);
        EngineSession e = h.connect("RALGO", g, clientTls(), "desk-1", Secret.of(LOGON_PASSWORD), true)) {
      g.bind(e.session());
      assertTrue(e.transport().isTls());
      final long px = Px.price("98");
      order(g, 1, px, 4_000_000);
      clob.submitInternal("DLR-A", 1007, false, px, 2_000_000, 0);
      e.quiesce();
      assertEquals(1, fillsBooked());
      assertEquals(1, clob.executionIds("RALGO").size());
      assertEquals(0, e.session().stats().sequenceErrors());
      assertEquals(0, e.transport().handshakeFailures() + e.transport().tlsErrors());
      assertEquals(0, clob.logonsRefused());
    }
  }

  @Test
  void aVenueCertificateWeDoNotTrustIsRefusedBeforeAnyLogon() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final ClobSimulator clob = clob(clock);
    final TlsConfig trustsSomeoneElse = TlsConfig.builder().trustStore(stranger.trustStore(), STORE).build();
    try (VenueHarness h = new VenueHarness(clob, clock, new AcceptorOptions(serverTls(), null, null, null));
        EngineSession e = h.prepare("RALGO", (s, m, d) -> { }, trustsSomeoneElse, null, null, true)) {
      final UncheckedIOException refused = assertThrows(UncheckedIOException.class, e::logon);
      assertTrue(refused.getMessage().contains("TLS handshake"), refused.getMessage());
      assertEquals(1, e.transport().handshakeFailures());
      assertEquals(0, e.session().stats().sent(), "no Logon was sent");
      assertTrue(clob.loggedOn().isEmpty());
    }
  }

  @Test
  void aPlainClientCannotTalkToATlsAcceptor() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final ClobSimulator clob = clob(clock);
    try (VenueHarness h = new VenueHarness(clob, clock, new AcceptorOptions(serverTls(), null, null, null));
        EngineSession e = h.prepare("RALGO", (s, m, d) -> { }, null, null, null, true)) {
      e.transport().connect(); // the Logon goes out in the clear and is not a TLS record
      e.pollUntil(() -> !e.transport().isConnected(), 10_000);
      assertTrue(e.session().state() != SessionState.ACTIVE);
      assertTrue(clob.loggedOn().isEmpty());
    }
  }

  @Test
  void mutualTlsAtTheSimulator() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final ClobSimulator clob = clob(clock);
    final TlsConfig server =
        TlsConfig.builder()
            .keyStore(venue.keyStore(), STORE)
            .trustStore(client.trustStore(), STORE)
            .requireClientCertificate(true)
            .build();
    final TlsConfig withCertificate =
        TlsConfig.builder().trustStore(venue.trustStore(), STORE).keyStore(client.keyStore(), STORE).build();
    try (VenueHarness h = new VenueHarness(clob, clock, new AcceptorOptions(server, null, null, null))) {
      try (EngineSession e = h.prepare("RALGO", (s, m, d) -> { }, clientTls(), null, null, true)) {
        try {
          e.transport().connect();
          e.pollUntil(() -> !e.transport().isConnected(), 10_000);
        } catch (final UncheckedIOException atHandshake) {
          assertTrue(atHandshake.getMessage().contains("TLS handshake"), atHandshake.getMessage());
        }
        assertTrue(e.session().state() != SessionState.ACTIVE);
        assertTrue(clob.loggedOn().isEmpty(), "no client certificate, no session");
      }
      try (EngineSession e = h.connect("RALGO", (s, m, d) -> { }, withCertificate, null, null, true)) {
        assertEquals(SessionState.ACTIVE, e.session().state());
        assertTrue(clob.loggedOn().contains("RALGO"));
      }
    }
  }

  @Test
  void aWrongPasswordGetsALogoutWithAReasonAndADisconnect() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final ClobSimulator clob = clob(clock);
    final AcceptorOptions options = new AcceptorOptions(null, "desk-1", Secret.of(LOGON_PASSWORD), null);
    try (VenueHarness h = new VenueHarness(clob, clock, options)) {
      final Refusal wrongPassword = new Refusal();
      try (EngineSession e = h.prepare("RALGO", wrongPassword, null, "desk-1", Secret.of("not-the-password"), true)) {
        expectRefusal(clob, e, wrongPassword);
      }
      assertEquals(1, clob.logonsRefused());

      final Refusal noCredentials = new Refusal();
      try (EngineSession e = h.prepare("RALGO", noCredentials, null, null, null, true)) {
        expectRefusal(clob, e, noCredentials);
      }
      assertEquals(2, clob.logonsRefused());

      final Refusal wrongUser = new Refusal();
      try (EngineSession e = h.prepare("RALGO", wrongUser, null, "desk-2", Secret.of(LOGON_PASSWORD), true)) {
        expectRefusal(clob, e, wrongUser);
      }
      assertEquals(3, clob.logonsRefused());
      assertEquals(3, clob.eventLog().stream().filter(l -> l.contains("logon refused RALGO")).count());

      // the right credentials are accepted, on the same acceptor, after the refusals
      try (EngineSession e = h.connect("RALGO", (s, m, d) -> { }, null, "desk-1", Secret.of(LOGON_PASSWORD), true)) {
        assertEquals(SessionState.ACTIVE, e.session().state());
        assertEquals(0, e.session().stats().logonsRefused());
      }
      assertEquals(3, clob.logonsRefused());
    }
  }

  @Test
  void acceptorOptionsAreValidated() {
    assertThrows(IllegalArgumentException.class, () -> new AcceptorOptions(null, "desk-1", null, null));
    assertThrows(IllegalArgumentException.class, () -> new AcceptorOptions(null, null, Secret.of("x"), null));
    assertThrows(IllegalArgumentException.class, () -> new AcceptorOptions(null, "", Secret.of("x"), null));
    assertThrows(IllegalArgumentException.class, () -> new AcceptorOptions(clientTls(), null, null, null));
    final AcceptorOptions options = new AcceptorOptions(serverTls(), "desk-1", Secret.of(LOGON_PASSWORD), null);
    assertFalse(options.toString().contains(LOGON_PASSWORD), options.toString());
    assertFalse(options.toString().contains(STORE_PASSWORD), options.toString());
  }

  @Test
  void aPlainAcceptorListensOnLoopbackOnly() throws Exception {
    final ManualClock clock = new ManualClock(Venues.T0);
    final ClobSimulator refused = clob(clock);
    final int port = VenueHarness.freePort();
    for (final String address : new String[] {"0.0.0.0", "192.0.2.10", "venue.example.com", "::"}) {
      final IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> refused.start(address, port, AcceptorOptions.PLAIN));
      assertTrue(e.getMessage().contains("refusing to listen on " + address + " without TLS"), e.getMessage());
    }
    assertEquals(0, refused.port(), "nothing was started");
    assertTrue(VenueSimulator.isLoopback("127.0.0.1") && VenueSimulator.isLoopback("localhost"));
    assertTrue(VenueSimulator.isLoopback("::1"));

    // and the default start is not reachable through another address of this machine
    final ClobSimulator clob = clob(clock);
    try (VenueHarness h = new VenueHarness(clob, clock)) {
      try (Socket loopback = new Socket()) {
        loopback.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), h.simulator().port()), 2_000);
        assertTrue(loopback.isConnected());
      }
      for (final InetAddress other : otherAddresses()) {
        try (Socket outside = new Socket()) {
          assertThrows(
              IOException.class,
              () -> outside.connect(new InetSocketAddress(other, clob.port()), 1_000),
              "reachable on " + other);
        }
      }
    }
  }

  /** This machine's addresses other than loopback (IPv4), if it has any. */
  private static List<InetAddress> otherAddresses() throws IOException {
    final List<InetAddress> addresses = new ArrayList<>();
    for (final NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      if (nic.isUp() && !nic.isLoopback()) {
        for (final InetAddress a : Collections.list(nic.getInetAddresses())) {
          if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
            addresses.add(a);
          }
        }
      }
    }
    return addresses;
  }

  @Test
  void theStandaloneProcessRefusesPlainFixBeyondLoopback() throws Exception {
    final int port = VenueHarness.freePort();
    final PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    final IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> VenueMain.run(new String[] {"--venue", "clob", "--port", Integer.toString(port), "--bind", "0.0.0.0",
                "--duration-millis", "50"}, sink));
    assertTrue(refused.getMessage().contains("refusing to listen for plain FIX on 0.0.0.0"), refused.getMessage());
    try (ServerSocket stillFree = new ServerSocket(port)) {
      assertTrue(stillFree.isBound(), "nothing was listening");
    }
  }

  @Test
  void theStandaloneProcessTakesTlsCredentialsAndAStoreDirectory(@TempDir final Path dir) throws Exception {
    final Path storePass = dir.resolve("store.pass");
    Files.writeString(storePass, STORE_PASSWORD + "\n");
    final Path logonPass = dir.resolve("logon.pass");
    Files.writeString(logonPass, LOGON_PASSWORD + "\n");
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      VenueMain.run(new String[] {"--venue", "clob", "--port", Integer.toString(VenueHarness.freePort()), "--bind", "localhost",
          "--tls-keystore", venue.keyStore().toString(), "--tls-keystore-password-file", storePass.toString(),
          "--username", "desk-1", "--password-file", logonPass.toString(), "--store-dir", dir.resolve("store").toString(),
          "--duration-millis", "100"}, out);
      // the same, with the secrets named by environment variable
      VenueMain.run(new String[] {"--venue", "clob", "--port", Integer.toString(VenueHarness.freePort()),
          "--tls-keystore", venue.keyStore().toString(), "--tls-keystore-password-env", "STORE_PASS",
          "--username", "desk-1", "--password-env", "LOGON_PASS", "--duration-millis", "100"}, out,
          Map.of("STORE_PASS", STORE_PASSWORD, "LOGON_PASS", LOGON_PASSWORD));
    }
    final String text = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("listening on localhost port") && text.contains("TLS, credentials required"), text);
    assertTrue(text.contains("listening on 127.0.0.1 port"), text);
    assertFalse(text.contains(STORE_PASSWORD) || text.contains(LOGON_PASSWORD), text);
    assertTrue(Files.exists(dir.resolve("store").resolve("CLOB.generation")));

    final PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    final String free = Integer.toString(VenueHarness.freePort());
    // a password with no user, a secret that is not set, a key store password that is wrong
    assertThrows(
        IllegalArgumentException.class,
        () -> VenueMain.run(new String[] {"--port", free, "--password-file", logonPass.toString(), "--duration-millis", "50"}, sink));
    final IllegalStateException unset =
        assertThrows(
            IllegalStateException.class,
            () -> VenueMain.run(new String[] {"--port", free, "--username", "desk-1", "--password-env", "NOT_SET",
                "--duration-millis", "50"}, sink, Map.of()));
    assertTrue(unset.getMessage().contains("NOT_SET"), unset.getMessage());
    final IllegalStateException wrongStorePassword =
        assertThrows(
            IllegalStateException.class,
            () -> VenueMain.run(new String[] {"--port", free, "--bind", "0.0.0.0", "--tls-keystore", venue.keyStore().toString(),
                "--tls-keystore-password-file", logonPass.toString(), "--duration-millis", "50"}, sink));
    assertFalse(wrongStorePassword.getMessage().contains(LOGON_PASSWORD), wrongStorePassword.getMessage());
    try (ServerSocket stillFree = new ServerSocket(Integer.parseInt(free))) {
      assertTrue(stillFree.isBound(), "none of the refused starts listened");
    }
  }
}
