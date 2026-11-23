package io.ratesalgo.app.gateway;

import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.HotPath;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.gateway.InstrumentMap;
import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.fix.gateway.VenueGateway;
import io.ratesalgo.fix.net.TcpInitiator;
import io.ratesalgo.fix.order.OrderStateTracker;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixSessionConfig;
import io.ratesalgo.fix.session.FixSessionHandler;
import io.ratesalgo.fix.session.MappedSequenceStore;
import io.ratesalgo.fix.session.SessionState;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import java.nio.ByteBuffer;
import java.nio.file.Path;
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
 * <p>Restart and reconnect (review finding L6). With {@link Link#resetOnLogon} off the session keeps
 * its sequence numbers in a {@link MappedSequenceStore} and resumes them at every Logon, so the
 * venue resends what we missed while down. A session that was active and then dropped reconnects
 * by itself under the same {@link ReconnectPolicy}, unless it was halted or disconnected on
 * purpose. A session that logs on again, after a drop or after a restart of the process, does not
 * simply report CONNECTED: if its venue holds orders ({@link Link#reconcileOrders}) it reports
 * RECONCILING, asks the venue for the status of every order it holds for us, passes the answers to
 * the loop and then reports RECONCILED; the loop keeps the venue down, and its quotes withdrawn,
 * until then.
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

  /**
   * How a session reaches its venue and what it keeps across connections.
   *
   * @param tls TLS settings, or null for plain TCP, which the transport allows to loopback only
   * @param username Logon Username (553), or null to send no credentials
   * @param password Logon Password (554); never logged or journaled
   * @param resetOnLogon true to restart both sequence numbers at every Logon (the simulator
   *     preset); false to resume them, which needs {@code sequenceFile}
   * @param sequenceFile where the sequence numbers are kept when resuming, else null
   * @param reconcileOrders true if the venue holds orders for us, so a new Logon after a drop or
   *     a restart is followed by an OrderMassStatusRequest before the loop may use the venue
   */
  public record Link(
      TlsConfig tls, String username, Secret password, boolean resetOnLogon, Path sequenceFile, boolean reconcileOrders) {

    /** Plain loopback TCP, no credentials, sequence numbers reset at every Logon. */
    public static final Link SIMULATOR = new Link(null, null, null, true, null, false);

    /** Validates the settings. */
    public Link {
      if (!resetOnLogon && sequenceFile == null) {
        throw new IllegalArgumentException("resuming sequence numbers needs a file to keep them in");
      }
      if ((username == null) != (password == null)) {
        throw new IllegalArgumentException("give a username and a password, or neither");
      }
    }

    /** These settings with or without the order reconciliation. */
    public Link withReconcileOrders(final boolean reconcile) {
      return new Link(tls, username, password, resetOnLogon, sequenceFile, reconcile);
    }

    /** These settings keeping sequence numbers in another file. */
    public Link withSequenceFile(final Path file) {
      return new Link(tls, username, password, resetOnLogon, file, reconcileOrders);
    }
  }

  /** Asked each duty cycle whether the loops have read everything this process has given them. */
  @FunctionalInterface
  public interface InboundDrained {

    /** Never: the resume point then stays where the store was opened. */
    InboundDrained NEVER = () -> false;

    /** True when every inbound ring is empty. */
    boolean drained();
  }

  /** How long the venue has to answer an OrderMassStatusRequest before it is sent again. */
  static final long RECONCILE_TIMEOUT_NANOS = 5_000_000_000L;

  /** Unanswered OrderMassStatusRequests before the connection is dropped and made again. */
  static final int RECONCILE_RETRIES = 2;

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
  private final boolean reconcileOrders;
  private final MappedSequenceStore sequences;
  private InboundDrained drained = InboundDrained.NEVER;
  private long safeInSeq;
  /** True once this session, or the process before a restart, has been logged on before. */
  private boolean loggedOnBefore;
  private boolean reconciling;
  private long reconcileRequestId;
  private long reconcileSeen;
  private long reconcileDeadlineNanos;
  private int reconcileRetries;
  private long reconciliations;
  private long drops;
  private long logonsRefused;
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
    this(venueId, statusId, compIds, host, port, instruments, inbound, clock, outbound, reconnect, withdraw,
        Link.SIMULATOR);
  }

  /** A session to {@code host:port} with the given policies, transport security and resume settings. */
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
      final WithdrawPolicy withdraw,
      final Link link) {
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
    this.reconcileOrders = link.reconcileOrders();
    // plain TCP is refused by the transport for any host that is not loopback
    this.tcp =
        new TcpInitiator(
            host, port, 1 << 20, TcpInitiator.DEFAULT_CONNECT_TIMEOUT_NANOS, TcpInitiator.DEFAULT_MAX_WRITE_SPINS,
            link.tls());
    FixSessionConfig config = new FixSessionConfig(compIds[0], compIds[1], 30, link.resetOnLogon());
    if (link.username() != null) {
      config = config.withCredentials(link.username(), link.password());
    }
    this.session = new FixSession(config, clock, tcp, this);
    gateway.bind(session);
    tcp.attach(session);
    if (link.resetOnLogon()) {
      this.sequences = null;
    } else {
      // After a clean stop the numbers are resumed as they stood. After a crash the inbound number
      // goes back to the last one known to have reached the loops' journals, so the venue resends
      // whatever may have been lost between this thread and a loop; the loop drops what it has
      // already booked by execution id.
      this.sequences = MappedSequenceStore.open(link.sequenceFile());
      this.loggedOnBefore = sequences.existed();
      this.safeInSeq = sequences.resumeInSeq();
      session.setSequenceNumbers(sequences.nextOutSeq(), sequences.resumeInSeq());
      session.sequenceStore(sequences);
      sequences.safeInSeq(safeInSeq);
    }
  }

  /** Sets who says the loops have read everything; the resume point follows it. Before connecting. */
  public void inboundDrained(final InboundDrained signal) {
    this.drained = signal;
  }

  /**
   * Tells the session the process restarted on its journals: its next Logon is a return, not a
   * first arrival, and is reconciled as one. Before connecting.
   */
  public void recovered() {
    loggedOnBefore = true;
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
    } else if (reconciling && clock.nanoTime() - reconcileDeadlineNanos >= 0) {
      work += reconcileTimedOut();
    }
    if (sequences != null) {
      final long in = session.expectedInSeq();
      if (in != safeInSeq && drained.drained()) {
        // every message below this number has been read, and so journaled, by a loop
        safeInSeq = in;
        sequences.safeInSeq(in);
      }
    }
    return work;
  }

  /** The venue did not finish its order status in time: ask again, then drop the connection. */
  private int reconcileTimedOut() {
    if (reconcileRetries >= RECONCILE_RETRIES || !isActive()) {
      reconciling = false;
      signal.reconciling(false);
      tcp.disconnect(); // a reconnect follows under the policy, and with it another attempt
    } else {
      reconcileRetries++;
      requestOrderStatus();
    }
    return 1;
  }

  private void requestOrderStatus() {
    reconcileRequestId++;
    reconcileSeen = 0;
    reconcileDeadlineNanos = clock.nanoTime() + RECONCILE_TIMEOUT_NANOS;
    gateway.requestOrderMassStatus(reconcileRequestId);
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
    if (reconciling
        && message.msgType() == MsgType.EXECUTION_REPORT
        && message.getLong(FixTags.MASS_STATUS_REQ_ID, 0) == reconcileRequestId) {
      // one line of the answer; the loop has it already. The last line, or the report that says
      // there are none, ends the reconciliation.
      reconcileSeen++;
      if (reconcileSeen >= message.getLong(FixTags.TOT_NUM_REPORTS, 0)) {
        reconciling = false;
        signal.reconciling(false);
        reconciliations++;
        publishStatus(ControlMessages.STATUS_RECONCILED);
      }
    }
  }

  @Override
  public void onLogout(final FixSession fixSession, final FixParser message, final boolean logonRefused) {
    if (logonRefused) {
      // wrong credentials do not get better by retrying, and a venue may lock the account
      logonsRefused++;
      wanted = false;
      nextAttemptNanos = Long.MAX_VALUE;
    }
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
    final boolean returning = loggedOnBefore;
    loggedOnBefore = true;
    if (returning && reconcileOrders && !halted) {
      reconciling = true;
      signal.reconciling(true);
      reconcileRetries = 0;
      publishStatus(ControlMessages.STATUS_RECONCILING);
      requestOrderStatus();
    } else {
      publishStatus(ControlMessages.STATUS_CONNECTED);
    }
  }

  @Override
  public void onDisconnect(final FixSession fixSession) {
    disconnects++;
    live.onDisconnect();
    gateway.onDisconnect(fixSession);
    if (reconciling) {
      reconciling = false;
      signal.reconciling(false);
    }
    publishStatus(ControlMessages.STATUS_DISCONNECTED);
    if (!loggedOnThisConnection) {
      attemptFailed();
    } else if (wanted && reconnect.maxAttempts() > 0) {
      // An active session the venue or the network dropped comes back by itself, after the first
      // backoff. Not after disconnect() or a halt: both stop the session being wanted first.
      drops++;
      loggedOnThisConnection = false;
      failedAttempts = 1;
      nextAttemptNanos = clock.nanoTime() + reconnect.backoffNanos(1);
    }
    if (halted) {
      publishHaltState();
    }
  }

  @Override
  public void onSessionReject(final FixSession fixSession, final FixParser message) {
    gateway.onSessionReject(fixSession, message);
  }

  private void publishStatus(final byte value) {
    final int length = ControlMessages.encodeGatewayStatus(status, 0, clock.epochNanos(), statusId, value);
    inbound.offer(status, 0, length);
  }

  /** True while the venue's order status is being collected after a new Logon. Any thread. */
  public boolean isReconciling() {
    return signal.reconciling();
  }

  /** Order reconciliations the venue completed. */
  public long reconciliations() {
    return reconciliations;
  }

  /** Times an active session was dropped and a reconnect was scheduled. */
  public long drops() {
    return drops;
  }

  /** Logons the venue refused; the session does not retry after one. */
  public long logonsRefused() {
    return logonsRefused;
  }

  /** The inbound sequence number a restart after a crash would resume from; 0 when not resuming. */
  public long safeInSeq() {
    return sequences == null ? 0L : safeInSeq;
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
    wanted = false;
    tcp.close();
    if (sequences != null) {
      sequences.close();
    }
  }
}
