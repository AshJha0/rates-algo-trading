package io.ratesalgo.venues.sim;

import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.common.time.Clock;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import quickfix.Application;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.FileStoreFactory;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.MessageStoreFactory;
import quickfix.RejectLogon;
import quickfix.RuntimeError;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.mina.ssl.SSLSupport;

/**
 * Base class of the three venue simulators: a QuickFIX/J acceptor with one session per engine
 * counterparty, a deterministic event queue driven by a {@link Clock}, a seeded {@link SimRng},
 * and cancel-on-disconnect hooks.
 *
 * <p>Threading: QuickFIX/J delivers messages on its own thread and the owner advances time on
 * another, so every entry point (message, logon, logout, time) runs while holding this object's
 * monitor. Simulators are test and research infrastructure, not hot-path code; the lock keeps a
 * run's event order a function of message order and clock only.
 *
 * <p>Listening ({@link AcceptorOptions}): plain FIX is bound to loopback and nothing else; any
 * other address needs TLS (QuickFIX/J's MINA filter over the JDK engine, TLS 1.3 unless the
 * settings name more). With a username and password set, a Logon without the matching 553 and 554
 * is answered with a Logout and a disconnect. With a store directory the venue's sequence numbers
 * and sent messages are kept on disk, so an engine that reconnects after the simulator restarts
 * can resume and have its fills resent, and ids continue above those of earlier runs.
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
  /** Atomic because start() sets the base under the lock and subclasses draw ids under it too. */
  private final AtomicLong idSeq = new AtomicLong();
  private long idBase;
  private long sendFailures;
  private long logonsRefused;
  private Secret requiredUsername;
  private Secret requiredPassword;
  /** Set once before start; read by QuickFIX/J's threads. */
  private volatile SimSequencer sequencer;
  private int sequencerSlot;

  /** Text of the Logout that answers a Logon with wrong or missing credentials. */
  public static final String LOGON_REFUSED_TEXT = "logon refused: invalid credentials";

  /** The address plain FIX listens on. */
  public static final String LOOPBACK = "127.0.0.1";

  /**
   * Ids of one run are below two to this power; a run with a store directory starts at its
   * generation shifted by it, so ids never repeat across restarts.
   */
  static final int GENERATION_SHIFT = 32;

  /**
   * Highest generation: ids stay below 2^43, the bound the router's child order book puts on an
   * ExecID (ChildOrderBook.MAX_EXEC_ID).
   */
  static final long MAX_GENERATION = (1L << (43 - GENERATION_SHIFT)) - 1;

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

  /**
   * Hands this venue's work to a sequencer: logons, logouts and application messages are then
   * queued by the FIX threads and run by whichever thread calls {@link SimSequencer#drain}, in the
   * order the sequencer fixes. Venues that share a market model must share one, because the model
   * has one owner. Call before {@link #start}.
   */
  public final synchronized void sequenceWith(final SimSequencer shared) {
    if (acceptor != null) {
      throw new IllegalStateException(compId + " is already listening");
    }
    sequencerSlot = shared.register(this);
    sequencer = shared;
  }

  /** The venue's CompID. */
  public final String compId() {
    return compId;
  }

  /** TCP port the acceptor listens on, 0 before {@link #start}. */
  public final synchronized int port() {
    return port;
  }

  /** Starts a plain acceptor on a loopback port: {@link AcceptorOptions#PLAIN}. */
  public final synchronized void start(final int listenPort) {
    start(listenPort, AcceptorOptions.PLAIN);
  }

  /** Starts the acceptor on a loopback port with the given options. */
  public final synchronized void start(final int listenPort, final AcceptorOptions options) {
    start(LOOPBACK, listenPort, options);
  }

  /**
   * Starts the acceptor on an address and port.
   *
   * @param bindAddress the local address to listen on; anything but loopback needs TLS
   * @throws IllegalArgumentException if plain FIX is asked to listen beyond loopback
   * @throws IllegalStateException if the acceptor cannot start, or the id generation kept in the
   *     store directory has reached its limit
   */
  public final synchronized void start(final String bindAddress, final int listenPort, final AcceptorOptions options) {
    final TlsConfig tls = options.tls();
    if (tls == null && !isLoopback(bindAddress)) {
      throw new IllegalArgumentException(
          "plain FIX is for loopback only: refusing to listen on " + bindAddress + " without TLS");
    }
    final SessionSettings settings = new SessionSettings();
    settings.setString("ConnectionType", "acceptor");
    settings.setString("SocketAcceptAddress", bindAddress);
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
    if (tls != null) {
      applyTls(settings, tls);
    }
    final MessageStoreFactory stores;
    if (options.storeDir() == null) {
      stores = new MemoryStoreFactory();
    } else {
      final long base = nextGeneration(options.storeDir()) << GENERATION_SHIFT;
      idSeq.accumulateAndGet(base, Math::max);
      idBase = base;
      settings.setString(FileStoreFactory.SETTING_FILE_STORE_PATH, options.storeDir().toString());
      stores = new FileStoreFactory(settings);
    }
    requiredUsername = options.username() == null ? null : Secret.of(options.username());
    requiredPassword = options.password();
    for (final String cp : counterparties) {
      final SessionID id = new SessionID("FIX.4.4", compId, cp);
      settings.setString(id, "BeginString", "FIX.4.4");
      sessions.put(cp, id);
    }
    try {
      acceptor =
          new SocketAcceptor(this, stores, settings, null, new DefaultMessageFactory());
      acceptor.start();
    } catch (ConfigError | RuntimeError e) {
      // QuickFIX/J's own message names settings and files, never their values
      throw new IllegalStateException("cannot start " + compId + " on " + bindAddress + ":" + listenPort, e);
    }
    port = listenPort;
  }

  /**
   * QuickFIX/J's TLS settings from ours. Its settings API takes passwords as strings, so a copy
   * of each lives in the settings object for as long as the acceptor does; it is never logged
   * (the acceptor has no log factory) and never put in a message of ours.
   */
  private static void applyTls(final SessionSettings settings, final TlsConfig tls) {
    settings.setString(SSLSupport.SETTING_USE_SSL, "Y");
    settings.setString(SSLSupport.SETTING_KEY_STORE_NAME, tls.keyStore().toString());
    settings.setString(SSLSupport.SETTING_KEY_STORE_PWD, text(tls.keyStorePassword()));
    settings.setString(SSLSupport.SETTING_KEY_STORE_TYPE, "PKCS12");
    settings.setString(SSLSupport.SETTING_ENABLED_PROTOCOLS, String.join(",", tls.protocols()));
    if (tls.requireClientCertificate()) {
      settings.setString(SSLSupport.SETTING_NEED_CLIENT_AUTH, "Y");
      settings.setString(SSLSupport.SETTING_TRUST_STORE_NAME, tls.trustStore().toString());
      settings.setString(SSLSupport.SETTING_TRUST_STORE_PWD, text(tls.trustStorePassword()));
      settings.setString(SSLSupport.SETTING_TRUST_STORE_TYPE, "PKCS12");
    }
  }

  private static String text(final Secret secret) {
    if (secret == null) {
      return "";
    }
    final char[] chars = secret.chars();
    final String value = new String(chars);
    java.util.Arrays.fill(chars, (char) 0);
    return value;
  }

  /** True for a loopback address literal or {@code localhost}; no other name counts. */
  public static boolean isLoopback(final String address) {
    if ("localhost".equalsIgnoreCase(address)) {
      return true;
    }
    try {
      return InetAddress.ofLiteral(address).isLoopbackAddress();
    } catch (final IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Reads the generation kept in the store directory, writes the next one and returns the one
   * read: 0 for the first run, 1 for the second, and so on. The trading side drops a fill whose
   * ExecID it has seen, so a restarted venue must not hand out the ids of its last run again.
   */
  private long nextGeneration(final Path storeDir) {
    final Path file = storeDir.resolve(compId + ".generation");
    try {
      Files.createDirectories(storeDir);
      final long generation =
          Files.exists(file) ? Long.parseLong(Files.readString(file, StandardCharsets.US_ASCII).strip()) : 0;
      if (generation < 0 || generation > MAX_GENERATION) {
        throw new IllegalStateException(
            compId + " has been restarted " + generation + " times on the store " + storeDir + ", the most its"
                + " ids allow (" + MAX_GENERATION + "); start it on an empty store directory");
      }
      Files.writeString(file, Long.toString(generation + 1), StandardCharsets.US_ASCII);
      return generation;
    } catch (final IOException | NumberFormatException e) {
      throw new IllegalStateException("cannot read or write the id generation file " + file, e);
    }
  }

  /** Logons refused for wrong or missing credentials. */
  public final synchronized long logonsRefused() {
    return logonsRefused;
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

  /**
   * Where this run's ids start: 0, or with a store directory the restart generation shifted up.
   * A simulator that numbers something with a counter of its own (RFQs) adds this to it, so those
   * ids do not repeat across restarts either: the engine drops an RFQ whose id it has seen.
   */
  protected final synchronized long idBase() {
    return idBase;
  }

  /** Next id from a per-simulator sequence (order ids, exec ids, quote response ids). */
  protected final long nextId() {
    return idSeq.incrementAndGet();
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
    final String cp = sessionId.getTargetCompID();
    final SimSequencer shared = sequencer;
    if (shared != null) {
      shared.offer(sequencerSlot, () -> logon(cp));
    } else {
      logon(cp);
    }
  }

  private synchronized void logon(final String cp) {
    loggedOn.add(cp);
    log("logon " + cp);
    onCounterpartyLogon(cp);
  }

  @Override
  public final void onLogout(final SessionID sessionId) {
    final String cp = sessionId.getTargetCompID();
    final SimSequencer shared = sequencer;
    if (shared != null) {
      shared.offer(sequencerSlot, () -> logout(cp));
    } else {
      logout(cp);
    }
  }

  private synchronized void logout(final String cp) {
    if (loggedOn.remove(cp)) {
      log("logout " + cp);
      onCounterpartyLogout(cp);
    }
  }

  @Override
  public final void toAdmin(final Message message, final SessionID sessionId) {
    // no venue-specific admin fields
  }

  /**
   * Checks the credentials of a Logon when the acceptor asks for them; everything else at session
   * level belongs to QuickFIX/J. A refusal makes QuickFIX/J send a Logout with the text and close
   * the connection. The text is the same whatever was wrong and repeats nothing that was sent.
   */
  @Override
  public final void fromAdmin(final Message message, final SessionID sessionId) throws RejectLogon {
    final Secret username;
    final Secret password;
    synchronized (this) {
      username = requiredUsername;
      password = requiredPassword;
    }
    if (username == null || !"A".equals(Fix.type(message))) {
      return;
    }
    // both comparisons always run, each in constant time: which half was wrong does not show
    final boolean userOk = username.matches(field(message, 553));
    final boolean passwordOk = password.matches(field(message, 554));
    if (!(userOk && passwordOk)) {
      synchronized (this) {
        logonsRefused++;
        log("logon refused " + sessionId.getTargetCompID());
      }
      throw new RejectLogon(LOGON_REFUSED_TEXT);
    }
  }

  private static String field(final Message message, final int tag) {
    try {
      return message.isSetField(tag) ? message.getString(tag) : null;
    } catch (final FieldNotFound e) {
      return null;
    }
  }

  @Override
  public final void toApp(final Message message, final SessionID sessionId) {
    // nothing to add on the way out
  }

  @Override
  public final void fromApp(final Message message, final SessionID sessionId) {
    final String cp = sessionId.getTargetCompID();
    final SimSequencer shared = sequencer;
    if (shared != null) {
      // the FIX thread only queues: the message is handled by the sequencer's owner
      shared.offer(sequencerSlot, () -> message(cp, message));
    } else {
      message(cp, message);
    }
  }

  private synchronized void message(final String cp, final Message message) {
    onMessage(cp, message);
  }

  /** A counterparty logged on. */
  protected void onCounterpartyLogon(final String counterparty) {}

  /** A counterparty logged out or disconnected: cancel-on-disconnect runs here. */
  protected abstract void onCounterpartyLogout(String counterparty);

  /** An application message from a counterparty. */
  protected abstract void onMessage(String counterparty, Message message);
}
