package io.ratesalgo.venues.sim;

import io.ratesalgo.common.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import quickfix.Application;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.RuntimeError;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

/**
 * Base class of the three venue simulators: a QuickFIX/J acceptor with one session per engine
 * counterparty, a deterministic event queue driven by a {@link Clock}, a seeded {@link SimRng},
 * and cancel-on-disconnect hooks.
 *
 * <p>Threading: QuickFIX/J delivers messages on its own thread and the owner advances time on
 * another, so every entry point (message, logon, logout, time) runs while holding this object's
 * monitor. Simulators are test and research infrastructure, not hot-path code; the lock keeps a
 * run's event order a function of message order and clock only.
 */
public abstract class VenueSimulator implements Application, AutoCloseable {

  /** Clock the simulator stamps and schedules with. */
  protected final Clock clock;

  /** The only random source. */
  protected final SimRng rng;

  private final String compId;
  private final List<String> counterparties;
  private final Map<String, SessionID> sessions = new HashMap<>();
  private final Set<String> loggedOn = new LinkedHashSet<>();
  private final PriorityQueue<Event> events = new PriorityQueue<>();
  private final List<String> eventLog = new ArrayList<>();
  private SocketAcceptor acceptor;
  private int port;
  private long eventSeq;
  private long idSeq;
  private long sendFailures;

  private record Event(long atNanos, long seq, Runnable action) implements Comparable<Event> {
    @Override
    public int compareTo(final Event o) {
      final int c = Long.compare(atNanos, o.atNanos);
      return c != 0 ? c : Long.compare(seq, o.seq);
    }
  }

  /**
   * Creates a simulator.
   *
   * @param compId the venue's SenderCompID
   * @param counterparties engine CompIDs allowed to log on
   * @param clock time source
   * @param seed RNG seed
   */
  protected VenueSimulator(
      final String compId, final List<String> counterparties, final Clock clock, final long seed) {
    this.compId = compId;
    this.counterparties = List.copyOf(counterparties);
    this.clock = clock;
    this.rng = new SimRng(seed);
  }

  /** The venue's CompID. */
  public final String compId() {
    return compId;
  }

  /** TCP port the acceptor listens on, 0 before {@link #start}. */
  public final synchronized int port() {
    return port;
  }

  /** Starts the acceptor on a port. */
  public final synchronized void start(final int listenPort) {
    final SessionSettings settings = new SessionSettings();
    settings.setString("ConnectionType", "acceptor");
    settings.setLong("SocketAcceptPort", listenPort);
    settings.setString("SocketReuseAddress", "Y");
    settings.setString("NonStopSession", "Y");
    settings.setString("StartTime", "00:00:00");
    settings.setString("EndTime", "00:00:00");
    settings.setLong("HeartBtInt", 30);
    settings.setString("UseDataDictionary", "Y");
    settings.setString("DataDictionary", "FIX44.xml");
    settings.setString("ValidateUserDefinedFields", "N");
    settings.setString("AllowUnknownMsgFields", "Y");
    settings.setString("CheckLatency", "N");
    settings.setString("PersistMessages", "Y");
    settings.setString("ResetOnLogon", "N");
    settings.setString("ResetOnLogout", "N");
    settings.setString("ResetOnDisconnect", "N");
    for (final String cp : counterparties) {
      final SessionID id = new SessionID("FIX.4.4", compId, cp);
      settings.setString(id, "BeginString", "FIX.4.4");
      sessions.put(cp, id);
    }
    try {
      acceptor =
          new SocketAcceptor(
              this, new MemoryStoreFactory(), settings, null, new DefaultMessageFactory());
      acceptor.start();
    } catch (ConfigError | RuntimeError e) {
      throw new IllegalStateException("cannot start " + compId + " on port " + listenPort, e);
    }
    port = listenPort;
  }

  @Override
  public final void close() {
    final SocketAcceptor a;
    synchronized (this) {
      a = acceptor;
      acceptor = null;
    }
    if (a != null) {
      a.stop(true);
    }
  }

  /** Counterparties currently logged on. */
  public final synchronized Set<String> loggedOn() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(loggedOn));
  }

  /** The QuickFIX/J session for a counterparty, or null before {@link #start}. */
  public final Session session(final String counterparty) {
    final SessionID id = sessions.get(counterparty);
    return id == null ? null : Session.lookupSession(id);
  }

  /** Lines describing what the simulator did, in order (used by determinism tests). */
  public final synchronized List<String> eventLog() {
    return List.copyOf(eventLog);
  }

  /** Messages that could not be sent because the session was gone. */
  public final synchronized long sendFailures() {
    return sendFailures;
  }

  /** Appends to the event log. */
  protected final void log(final String line) {
    eventLog.add(clock.epochNanos() + " " + line);
  }

  /** Next id from a per-simulator sequence (order ids, exec ids, quote response ids). */
  protected final long nextId() {
    return ++idSeq;
  }

  /** Schedules an action at a monotonic time. */
  protected final void schedule(final long atNanos, final Runnable action) {
    events.add(new Event(atNanos, eventSeq++, action));
  }

  /** Monotonic time of the next scheduled event, or {@code Long.MAX_VALUE}. */
  public final synchronized long nextEventNanos() {
    final Event e = events.peek();
    return e == null ? Long.MAX_VALUE : e.atNanos();
  }

  /** Runs every event due at the clock's current time. Call after moving the clock. */
  public final synchronized void onTime() {
    final long now = clock.nanoTime();
    while (!events.isEmpty() && events.peek().atNanos() <= now) {
      events.poll().action().run();
    }
  }

  /** Runs an action under the simulator's lock (harness and test access). */
  public final synchronized void locked(final Runnable action) {
    action.run();
  }

  /** Sends a message to a counterparty; returns false if it is not logged on. */
  protected final boolean send(final String counterparty, final Message message) {
    final SessionID id = sessions.get(counterparty);
    if (id == null || !loggedOn.contains(counterparty)) {
      sendFailures++;
      return false;
    }
    try {
      return Session.sendToTarget(message, id);
    } catch (SessionNotFound e) {
      sendFailures++;
      return false;
    }
  }

  /** True if the counterparty is logged on. */
  protected final boolean isLoggedOn(final String counterparty) {
    return loggedOn.contains(counterparty);
  }

  // ------------------------------------------------------------------ QuickFIX/J callbacks

  @Override
  public final void onCreate(final SessionID sessionId) {
    // sessions are created at start-up from the configured counterparties
  }

  @Override
  public final void onLogon(final SessionID sessionId) {
    synchronized (this) {
      final String cp = sessionId.getTargetCompID();
      loggedOn.add(cp);
      log("logon " + cp);
      onCounterpartyLogon(cp);
    }
  }

  @Override
  public final void onLogout(final SessionID sessionId) {
    synchronized (this) {
      final String cp = sessionId.getTargetCompID();
      if (loggedOn.remove(cp)) {
        log("logout " + cp);
        onCounterpartyLogout(cp);
      }
    }
  }

  @Override
  public final void toAdmin(final Message message, final SessionID sessionId) {
    // no venue-specific admin fields
  }

  @Override
  public final void fromAdmin(final Message message, final SessionID sessionId) {
    // session-level handling belongs to QuickFIX/J
  }

  @Override
  public final void toApp(final Message message, final SessionID sessionId) {
    // nothing to add on the way out
  }

  @Override
  public final void fromApp(final Message message, final SessionID sessionId) {
    synchronized (this) {
      onMessage(sessionId.getTargetCompID(), message);
    }
  }

  /** A counterparty logged on. */
  protected void onCounterpartyLogon(final String counterparty) {}

  /** A counterparty logged out or disconnected: cancel-on-disconnect runs here. */
  protected abstract void onCounterpartyLogout(String counterparty);

  /** An application message from a counterparty. */
  protected abstract void onMessage(String counterparty, Message message);
}
