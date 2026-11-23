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
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
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
 * attempt so that every attempt is journaled by the loop (DR-INT-09).
 *
 * <p>The session can also withdraw by itself, without the loop ({@link #halt}): it keeps its own
 * record of the quotes and orders working at the venue ({@link LiveSet}), and once halted it lets
 * nothing from the loop out, cancels everything in that record, repeats the cancels the venue has
 * not answered, and logs out if they stay unanswered or if it was asked to. A logout is the
 * stronger step: the venue then cancels on disconnect whatever it still holds for us. A halt is
 * final for the process; there is no way back to quoting short of a restart.
 *
 * <p>Not thread-safe; one thread owns a session. Other threads may read {@link #haltState} and
 * {@link #unconfirmed}, and may call {@link #abort}.
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

  /** {@link #haltState}: trading normally. */
  public static final int RUNNING = 0;

  /** {@link #haltState}: halted, with quotes or orders not yet confirmed gone. */
  public static final int HALTING = 1;

  /** {@link #haltState}: halted, and nothing of ours is working at the venue. */
  public static final int WITHDRAWN = 2;

  /** Quotes and orders tracked at once before the record stops being complete. */
  static final int LIVE_CAPACITY = 8192;

  private final LiveSet live;
  private final MessageHeaderDecoder outHeader = new MessageHeaderDecoder();
  private final long cancelRetryNanos;
  private final int maxCancelRounds;
  private final long logoutGraceNanos;
  private boolean halted;
  private boolean logoutRequested;
  private int cancelRounds;
  private long nextCancelNanos;
  private long logoutDeadlineNanos;
  private long blockedOutbound;
  private int publishedState;
  private int publishedUnconfirmed;
  /** What other threads read; written by the owner when a value changes, never per duty cycle. */
  private final HaltSignal signal = new HaltSignal();

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
    this(venueId, statusId, compIds, host, port, instruments, inbound, clock, outbound, reconnect,
        WithdrawPolicy.DEFAULT);
  }

  /**
   * How a halted session presses its cancels.
   *
   * @param cancelRetryNanos wait before cancels the venue has not answered are sent again
   * @param maxCancelRounds repeats before the session gives up on acknowledgements and logs out
   * @param logoutGraceNanos wait for the venue to answer our Logout before the socket is closed
   */
  public record WithdrawPolicy(long cancelRetryNanos, int maxCancelRounds, long logoutGraceNanos) {

    /** Cancels repeated every 250 ms, three times, then Logout; one second for the venue's Logout. */
    public static final WithdrawPolicy DEFAULT = new WithdrawPolicy(250_000_000L, 3, 1_000_000_000L);

    /** Validates the settings. */
    public WithdrawPolicy {
      if (cancelRetryNanos <= 0 || maxCancelRounds < 0 || logoutGraceNanos <= 0) {
        throw new IllegalArgumentException("withdraw policy needs positive waits and rounds of at least 0");
      }
    }
  }

  /** A session to {@code host:port} with the given reconnect and withdraw policies. */
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
      final ReconnectPolicy reconnect,
      final WithdrawPolicy withdraw) {
    this.live = new LiveSet(venueId, LIVE_CAPACITY);
    this.cancelRetryNanos = withdraw.cancelRetryNanos();
    this.maxCancelRounds = withdraw.maxCancelRounds();
    this.logoutGraceNanos = withdraw.logoutGraceNanos();
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
    if (halted) {
      return; // a halted session stays down
    }
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
    if (halted) {
      work += haltWork();
    }
    return work;
  }

  private void onOutbound(final int msgTypeId, final MutableDirectBuffer buffer, final int index, final int length) {
    outboundRecords++;
    if (halted) {
      // New quotes and orders must not go out. The loop's own cancels are dropped too: every
      // quote and order that reached the venue is in the live set, and the session is already
      // cancelling those and repeating the cancels until the venue answers.
      blockedOutbound++;
      return;
    }
    if (gateway.offer(buffer, index, length)) {
      outHeader.wrap(buffer, index);
      live.onSent(msgTypeId, buffer, index, outHeader.blockLength(), outHeader.version());
    }
  }

  @Override
  public void onApplicationMessage(final FixSession fixSession, final FixParser message, final boolean possDup) {
    live.onVenueMessage(message);
    gateway.onApplicationMessage(fixSession, message, possDup);
  }

  // ---------------------------------------------------------------- halt

  /**
   * Stops this session trading, from the gateway side: nothing more from the loop is sent, every
   * quote and order in the live set is cancelled, reconnects stop. With {@code logout} the session
   * also logs out after the cancels, so that the venue's cancel-on-disconnect covers anything the
   * live set missed; a later call may add the logout to a halt made without it. Idempotent; owner
   * thread only.
   */
  public void halt(final boolean logout) {
    if (!halted) {
      halted = true;
      wanted = false;
      nextAttemptNanos = Long.MAX_VALUE;
      cancelRounds = 0;
      if (isActive()) {
        live.cancelAll(gateway, clock.epochNanos());
      }
      nextCancelNanos = clock.nanoTime() + cancelRetryNanos;
    }
    if (logout && !logoutRequested) {
      beginLogout();
    }
    publishHaltState();
  }

  private void beginLogout() {
    logoutRequested = true;
    logoutDeadlineNanos = clock.nanoTime() + logoutGraceNanos;
    session.logout();
  }

  /** Halted duty: repeat unanswered cancels, escalate to Logout, close a session that will not end. */
  private int haltWork() {
    int work = 0;
    final long now = clock.nanoTime();
    if (logoutRequested) {
      if (tcp.isConnected() && now - logoutDeadlineNanos >= 0) {
        tcp.disconnect(); // the venue did not answer our Logout: drop the connection
        work++;
      }
    } else if (isActive() && (live.liveQuotes() + live.liveOrders() > 0 || live.overflowed())
        && now - nextCancelNanos >= 0) {
      if (cancelRounds >= maxCancelRounds || live.overflowed()) {
        beginLogout();
      } else {
        cancelRounds++;
        live.cancelAll(gateway, clock.epochNanos());
        nextCancelNanos = now + cancelRetryNanos;
      }
      work++;
    }
    publishHaltState();
    return work;
  }

  private void publishHaltState() {
    final boolean down = session.state() == SessionState.DISCONNECTED;
    final int left = down ? 0 : live.liveQuotes() + live.liveOrders();
    final boolean withdrawn = down || (!logoutRequested && left == 0 && !live.overflowed());
    final int state = withdrawn ? WITHDRAWN : HALTING;
    if (left != publishedUnconfirmed) {
      publishedUnconfirmed = left;
      signal.unconfirmed(left);
    }
    if (state != publishedState) {
      publishedState = state;
      signal.state(state);
    }
  }

  /** {@link #RUNNING}, {@link #HALTING} or {@link #WITHDRAWN}; readable from any thread. */
  public int haltState() {
    return signal.state();
  }

  /** While halted: quotes plus orders the venue has not yet confirmed gone. Any thread. */
  public int unconfirmed() {
    return signal.unconfirmed();
  }

  /**
   * Closes the socket from any thread, for when the owner thread is stuck or dead: the venue sees
   * the disconnect and cancels what we have working there. The owner, if it still runs, finds the
   * closed socket on its next duty cycle.
   */
  public void abort() {
    tcp.abort();
  }

  /** Quotes the session believes are working at the venue. Owner thread. */
  public int liveQuotes() {
    return live.liveQuotes();
  }

  /** Orders the session believes are working at the venue. Owner thread. */
  public int liveOrders() {
    return live.liveOrders();
  }

  /** Cancels the session itself sent while halted. */
  public long haltCancelsSent() {
    return live.cancelsSent();
  }

  /** Records from the loop not sent because the session was halted. */
  public long blockedOutbound() {
    return blockedOutbound;
  }

  /** True once {@link #halt} has been called. Owner thread. */
  public boolean isHalted() {
    return halted;
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
    live.onDisconnect();
    gateway.onDisconnect(fixSession);
    publishStatus(false);
    if (!loggedOnThisConnection) {
      attemptFailed();
    }
    if (halted) {
      publishHaltState();
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
