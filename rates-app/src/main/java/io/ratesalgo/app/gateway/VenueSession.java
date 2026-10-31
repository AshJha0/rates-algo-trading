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
 * timers, drain the outbound rings. Not thread-safe; one thread owns a session.
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

  /** Connects and sends the Logon; the GatewayStatus follows when the venue accepts it. */
  public void connect() {
    tcp.connect();
  }

  /** Drops the connection (chaos and shutdown); the loop is told through GatewayStatus. */
  public void disconnect() {
    tcp.disconnect();
  }

  /** True once the venue has accepted our Logon. */
  public boolean isActive() {
    return session.state() == SessionState.ACTIVE;
  }

  /** One duty cycle: socket, session timers, outbound rings. Returns the work done. */
  public int doWork() {
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
    gateway.onLogon(fixSession);
    publishStatus(true);
  }

  @Override
  public void onDisconnect(final FixSession fixSession) {
    disconnects++;
    gateway.onDisconnect(fixSession);
    publishStatus(false);
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
