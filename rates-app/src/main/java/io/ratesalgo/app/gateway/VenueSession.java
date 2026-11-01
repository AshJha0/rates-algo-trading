package io.ratesalgo.app.gateway;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.HotPath;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.gateway.InstrumentMap;
import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.fix.gateway.VenueGateway;
import io.ratesalgo.fix.net.TcpInitiator;
import io.ratesalgo.fix.order.OrderStateTracker;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixSessionConfig;
import io.ratesalgo.fix.session.FixSessionHandler;
import io.ratesalgo.fix.session.SessionState;
import java.nio.ByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;

/**
 * The venue binding for one FIX session (API_SPEC.md Section 18.5): P2's {@link VenueGateway} over
 * our FIX codec and a TCP initiator, wired to the ring-buffer contract of Section 9.
 *
 * <ul>
 *   <li>Inbound: FIX application messages become SBE in the gateway and go to the {@link
 *       InboundRouter}, which publishes them into the instrument groups' inbound rings. Logon and
 *       disconnect publish a GatewayStatus (template 65003) with this session's status id, which
 *       is how the loop learns that a session went down and pulls its quotes.
 *   <li>Outbound: each group's outbound ring for this venue is read here and every record is
 *       offered to the gateway, which writes the FIX message. A record that arrives while the
 *       session is down is dropped and counted by the gateway: the venue cancels our resting
 *       orders and quotes on disconnect, and the loop has already pulled its own view.
 * </ul>
 *
 * <p>{@link #doWork} is one duty cycle on the gateway thread: read the socket, run the session's
 * timers, drain the outbound rings, and make a reconnect attempt when one is due. A connection
 * whose Logon is not accepted (connect refused, closed by the venue before its Logon, or timed out)
 * is retried under the session's {@link ReconnectPolicy}, with a GatewayStatus RECONNECTING per
 * attempt so that every attempt is journaled by the loop (DR-INT-09). Not thread-safe; one thread
 * owns a session.
 */
@HotPath
public final class VenueSession implements FixSessionHandler {

  private final int statusId;
  private final VenueGateway gateway;
  private final MessageSink inbound;
  private final Clock clock;
  private final TcpInitiator tcp;
  private final FixSession session;
  private final RingBuffer[] outbound;
  private final MutableDirectBuffer status = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
  private final org.agrona.concurrent.MessageHandler drain = this::onOutbound;
  private long outboundRecords;
  private long logons;
  private long disconnects;
  private final ReconnectPolicy reconnect;
  /** True between {@link #connect} and {@link #disconnect}: the session should be up. */
  private boolean wanted;
  /** True once the venue accepted our Logon on the current connection. */
  private boolean loggedOnThisConnection;
  /** Failed attempts since the last accepted Logon. */
  private int failedAttempts;
  /** {@code nanoTime} of the next attempt, or {@link Long#MAX_VALUE} when none is due. */
  private long nextAttemptNanos = Long.MAX_VALUE;
  private long reconnectAttempts;
  private long reconnectGiveUps;

  /**
   * A session to {@code host:port}.
   *
   * @param venueId venue id stamped on inbound SBE messages
   * @param statusId gateway id carried by this session's GatewayStatus messages
   * @param compIds our CompID and the venue's
   * @param instruments instrument id to FIX SecurityID
   * @param inbound where inbound SBE goes
   * @param clock intervals from {@code nanoTime} for the session timers, stamps from {@code
   *     epochNanos}
   * @param outbound this venue's outbound ring in each group
   */
  public VenueSession(
      final int venueId,
      final int statusId,
      final String[] compIds,
      final String host,
      final int port,
      final InstrumentMap instruments,
      final MessageSink inbound,
      final Clock clock,
      final RingBuffer[] outbound) {
    this(venueId, statusId, compIds, host, port, instruments, inbound, clock, outbound, ReconnectPolicy.DEFAULT);
  }

  /** A session to {@code host:port} with the given reconnect policy (DR-INT-09). */
  public VenueSession(
      final int venueId,
      final int statusId,
      final String[] compIds,
      final String host,
      final int port,
      final InstrumentMap instruments,
      final MessageSink inbound,
      final Clock clock,
      final RingBuffer[] outbound,
      final ReconnectPolicy reconnect) {
    this.reconnect = reconnect;
    this.statusId = statusId;
    this.inbound = inbound;
    this.clock = clock;
    this.outbound = outbound.clone();
    this.gateway = new VenueGateway(venueId, instruments, inbound, clock, new OrderStateTracker(4096, 16384));
    this.tcp = new TcpInitiator(host, port, 1 << 20);
    this.session = new FixSession(new FixSessionConfig(compIds[0], compIds[1], 30, true), clock, tcp, this);
    gateway.bind(session);
    tcp.attach(session);
  }

  /**
   * Connects and sends the Logon; the GatewayStatus follows when the venue accepts it. If the
   * Logon is not accepted the session retries under its {@link ReconnectPolicy}. With {@link
   * ReconnectPolicy#NONE} a refused TCP connect throws, as before DR-INT-09.
   */
  public void connect() {
    wanted = true;
    failedAttempts = 0;
    nextAttemptNanos = Long.MAX_VALUE;
    if (reconnect.maxAttempts() == 0) {
      loggedOnThisConnection = false;
      tcp.connect();
      return;
    }
    attempt();
  }

  /** Drops the connection (chaos and shutdown) and stops retrying; the loop is told through GatewayStatus. */
  public void disconnect() {
    wanted = false;
    nextAttemptNanos = Long.MAX_VALUE;
    tcp.disconnect();
  }

  private void attempt() {
    loggedOnThisConnection = false;
    try {
      tcp.connect();
    } catch (final java.io.UncheckedIOException refused) {
      attemptFailed();
    }
  }

  /** The current connection ended or was refused before the venue accepted our Logon. */
  private void attemptFailed() {
    if (!wanted) {
      return;
    }
    if (failedAttempts >= reconnect.maxAttempts()) {
      reconnectGiveUps++;
      wanted = false;
      nextAttemptNanos = Long.MAX_VALUE;
      return;
    }
    failedAttempts++;
    nextAttemptNanos = clock.nanoTime() + reconnect.backoffNanos(failedAttempts);
  }

  /** True once the venue has accepted our Logon. */
  public boolean isActive() {
    return session.state() == SessionState.ACTIVE;
  }

  /** One duty cycle: socket, session timers, outbound rings. Returns the work done. */
  public int doWork() {
    if (nextAttemptNanos != Long.MAX_VALUE && clock.nanoTime() >= nextAttemptNanos) {
      nextAttemptNanos = Long.MAX_VALUE;
      reconnectAttempts++;
      final int length =
          ControlMessages.encodeGatewayReconnecting(status, 0, clock.epochNanos(), statusId, failedAttempts);
      inbound.offer(status, 0, length);
      attempt();
    }
    int work = tcp.poll();
    for (final RingBuffer ring : outbound) {
      work += ring.read(drain, 256);
    }
    return work;
  }

  private void onOutbound(final int msgTypeId, final MutableDirectBuffer buffer, final int index, final int length) {
    outboundRecords++;
    gateway.offer(buffer, index, length);
  }

  @Override
  public void onApplicationMessage(final FixSession fixSession, final FixParser message, final boolean possDup) {
    gateway.onApplicationMessage(fixSession, message, possDup);
  }

  @Override
  public void onLogon(final FixSession fixSession) {
    logons++;
    loggedOnThisConnection = true;
    failedAttempts = 0;
    gateway.onLogon(fixSession);
    publishStatus(true);
  }

  @Override
  public void onDisconnect(final FixSession fixSession) {
    disconnects++;
    gateway.onDisconnect(fixSession);
    publishStatus(false);
    if (!loggedOnThisConnection) {
      attemptFailed();
    }
  }

  @Override
  public void onSessionReject(final FixSession fixSession, final FixParser message) {
    gateway.onSessionReject(fixSession, message);
  }

  private void publishStatus(final boolean connected) {
    final int length =
        ControlMessages.encodeGatewayStatus(
            status,
            0,
            clock.epochNanos(),
            statusId,
            connected ? ControlMessages.STATUS_CONNECTED : ControlMessages.STATUS_DISCONNECTED);
    inbound.offer(status, 0, length);
  }

  /** The P2 gateway (its counters and order tracker). */
  public VenueGateway gateway() {
    return gateway;
  }

  /** The FIX session (sequence numbers, for quiescence checks). */
  public FixSession session() {
    return session;
  }

  /** Outbound records read from the rings. */
  public long outboundRecords() {
    return outboundRecords;
  }

  public long logons() {
    return logons;
  }

  /** Reconnect attempts made after a Logon was not accepted (DR-INT-09). */
  public long reconnectAttempts() {
    return reconnectAttempts;
  }

  /** Times the session gave up after {@link ReconnectPolicy#maxAttempts} failed retries. */
  public long reconnectGiveUps() {
    return reconnectGiveUps;
  }

  /** True while a reconnect attempt is scheduled. */
  public boolean reconnectPending() {
    return nextAttemptNanos != Long.MAX_VALUE;
  }

  /** The engine-side FIX session state, for diagnostics. */
  public SessionState state() {
    return session.state();
  }

  public long disconnects() {
    return disconnects;
  }

  /** Closes the socket. */
  public void close() {
    if (session.state() == SessionState.ACTIVE) {
      session.logout();
    }
    tcp.close();
  }
}
