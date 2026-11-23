package io.ratesalgo.app;

import io.ratesalgo.app.gateway.FeedBinding;
import io.ratesalgo.app.gateway.InboundRouter;
import io.ratesalgo.app.gateway.ReconnectPolicy;
import io.ratesalgo.app.gateway.VenueSession;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskLoopFactory;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.app.sim.ClobLiquidity;
import io.ratesalgo.app.sim.CurveFeed;
import io.ratesalgo.app.sim.SimClock;
import io.ratesalgo.app.sim.SimulatedVenues;
import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.core.AnchoredClock;
import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.DutyCycleRunner;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.IdleStrategies;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.JournalMode;
import io.ratesalgo.core.LoopCounters;
import io.ratesalgo.core.LoopJournals;
import io.ratesalgo.core.LoopRecovery;
import io.ratesalgo.core.LoopRunner;
import io.ratesalgo.core.RecoveryClock;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.journal.ChainHash;
import io.ratesalgo.journal.JournalRecovery;
import io.ratesalgo.journal.JournalWarmUp;
import io.ratesalgo.journal.RingJournal;
import io.ratesalgo.journal.SegmentedJournal;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.OrderCancelEncoder;
import io.ratesalgo.messages.sbe.OrderWithdrawEncoder;
import io.ratesalgo.messages.sbe.QuoteCancelEncoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawEncoder;
import io.ratesalgo.messages.sbe.RiskLimitBreachDecoder;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.sim.AcceptorOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;

/**
 * The assembled trading process (API_SPEC.md Section 18): P2's D2C and CLOB simulators in process
 * on a simulated clock, the FIX gateways and the book feed bound to the ring-buffer contract, one
 * event loop per instrument group with inbound, outbound and router journals, the curve feed and
 * the CLOB's resting liquidity.
 *
 * <p>Two ways to run it. {@link #step} drives everything on the calling thread: it moves simulated
 * time, runs the venues' due events and pumps gateways, feed and loops until the FIX sessions are
 * quiet; the smoke test and the recipe use it, so a seeded day runs the same way each time up to
 * the venues' socket timing. {@link #startThreads} runs each loop on its own {@link LoopRunner}
 * thread, each FIX session and the book feed on a thread of its own ({@code rates-d2c}, {@code
 * rates-clob}, {@code rates-feed}), and the simulated venues, the curve feed and the CLOB's
 * liquidity on {@code rates-venues}, which paces simulated time at a multiple of real time; the
 * process main uses it. Each of those threads has its own idle strategy and, on Linux, its own CPU
 * ({@link Tuning}; review finding L3, DECISIONS.md DR-R6-L02). The defaults are what the process
 * always did: the loop backs off, the gateway threads park 200 microseconds when idle, nothing is
 * pinned.
 *
 * <p>Safety (review findings L1 and L2). The loops pull their own quotes when they are told to, but
 * a loop that has died, hung or fallen behind cannot, so the withdrawal does not depend on them:
 *
 * <ul>
 *   <li>A halt is a volatile flag each session's thread reads every duty cycle. Once set, each venue
 *       session lets nothing more from the loops out, cancels every quote and order it knows to be
 *       working, repeats the cancels the venue has not answered and, if asked or if they stay
 *       unanswered, logs out so that the venue's cancel-on-disconnect applies ({@link
 *       VenueSession#halt}). A halt is final for the process.
 *   <li>{@link #kill} sets that flag first and then offers the journaled KillSwitch message, so
 *       replay still sees the kill as an input (DR-R2W-10) while the withdrawal itself needs
 *       neither a live loop nor room in the inbound ring. It answers whether the withdrawal was
 *       confirmed.
 *   <li>A supervisor thread ({@link #supervise}) watches the loop runners and the gateway
 *       threads. A loop that stopped or stalled halts the process with a logout. A session thread
 *       that died withdraws its own session as it goes down, and the other session's thread
 *       withdraws its own on the halt; if a session thread cannot, or is stuck, the supervisor
 *       closes that session's socket from outside, which is the one thing another thread may
 *       safely do to a session (DR-RV-S01 to S04). A feed or venue-simulator thread that died or
 *       stalled halts the process with a logout, which the session threads carry out.
 * </ul>
 *
 * <p>Restart (review finding L6). A journal directory that already holds a group's journals is a
 * process that stopped, cleanly or not, and is starting again. Before anything connects:
 *
 * <ol>
 *   <li>the journals are checked; a torn tail is cut and reported, a broken chain refuses to start
 *       ({@link JournalRecovery});
 *   <li>each loop replays its own inbound journal through the handlers it will run live, and what
 *       they publish is checked against the outbound journal ({@link LoopRecovery}). That is the
 *       only way state is restored: positions, risk, quotes, router children and hedge parents are
 *       whatever replay makes them, and replay is what the tests hold to an equal hash;
 *   <li>simulated time carries on from the last journaled frame;
 *   <li>each loop is told, as journaled inputs, that both sessions are down, and the book feed is
 *       made to rebuild: the venues cancelled on disconnect, so the loops pull their quotes and
 *       stop counting on orders the venue no longer holds.
 * </ol>
 *
 * <p>Then the sessions log on. The CLOB session reconciles before its loop may use the venue
 * ({@link VenueSession}), and the feed delivers a snapshot. Until all of that has happened the
 * loops' input health keeps quoting off and {@link #notReadyReason} says what is still missing.
 * With {@link Fix#resetOnLogon} off the sessions resume their sequence numbers, so the venues
 * resend fills sent while the process was down; a fill already journaled is dropped by its
 * execution id.
 *
 * <p>Instrument groups are currencies (DECISIONS.md DR-R2W-03): the risk engine and the hedger
 * aggregate by currency, so a group cannot split one. The universe is USD only, so there is one
 * group, {@code usd}; the inbound router and the journal layout take any number.
 */
public final class TradingProcess implements AutoCloseable {

  /** Interval of the curve feed, simulated time. */
  public static final long CURVE_INTERVAL_NANOS = 500_000_000L;

  /** Interval of the CLOB liquidity refresh, simulated time. */
  public static final long LIQUIDITY_INTERVAL_NANOS = 1_000_000_000L;

  /** Segment size of every loop journal. */
  public static final int SEGMENT_BYTES = 64 << 20;

  /**
   * Process settings.
   *
   * @param journalDir directory of the loops' journals (one subdirectory per group)
   * @param seed seed of the venues' random streams
   * @param settings the desks' parameters
   * @param startNanos simulated wall time at start, epoch nanoseconds
   * @param tuning threads, journals and supervision
   * @param fix how the FIX sessions reach the venues
   * @param d2cEndpoint where the D2C session connects instead of the in-process D2C simulator, or
   *     null for the simulator; the wired-desk benchmark puts its load generator here
   */
  public record Options(
      Path journalDir, long seed, DeskSettings settings, long startNanos, Tuning tuning, Fix fix, Endpoint d2cEndpoint) {

    /** Options with the in-process D2C simulator. */
    public Options(
        final Path journalDir, final long seed, final DeskSettings settings, final long startNanos, final Tuning tuning,
        final Fix fix) {
      this(journalDir, seed, settings, startNanos, tuning, fix, null);
    }

    /** Options with the simulator's FIX preset: plain loopback, no credentials, reset on logon. */
    public Options(
        final Path journalDir, final long seed, final DeskSettings settings, final long startNanos, final Tuning tuning) {
      this(journalDir, seed, settings, startNanos, tuning, Fix.SIMULATOR);
    }

    /** These options with other FIX settings. */
    public Options withFix(final Fix other) {
      return new Options(journalDir, seed, settings, startNanos, tuning, other, d2cEndpoint);
    }

    /** These options with the D2C session connecting to {@code endpoint} (null: the simulator). */
    public Options withD2cEndpoint(final Endpoint endpoint) {
      return new Options(journalDir, seed, settings, startNanos, tuning, fix, endpoint);
    }

    /** Options starting at {@link #DEFAULT_START}. */
    public Options(final Path journalDir, final long seed, final DeskSettings settings) {
      this(journalDir, seed, settings, DEFAULT_START);
    }

    /** Options with the default {@link Tuning}. */
    public Options(final Path journalDir, final long seed, final DeskSettings settings, final long startNanos) {
      this(journalDir, seed, settings, startNanos, Tuning.DEFAULT);
    }

    /** These options with another tuning. */
    public Options withTuning(final Tuning other) {
      return new Options(journalDir, seed, settings, startNanos, other, fix, d2cEndpoint);
    }
  }

  /**
   * A FIX acceptor outside the process.
   *
   * @param host its address; without TLS it must be loopback (the transport refuses anything else)
   * @param port its port
   * @param compId its SenderCompID
   */
  public record Endpoint(String host, int port, String compId) {}

  /**
   * How the FIX sessions reach the venues, and how the in-process simulators accept them.
   *
   * @param clientTls the initiators' TLS settings, or null for plain loopback TCP
   * @param acceptorTls the simulators' TLS settings; needed when {@code clientTls} is given
   * @param username Logon Username (553), or null for no credentials; the simulators then
   *     require the same pair
   * @param password Logon Password (554)
   * @param resetOnLogon true restarts sequence numbers at every Logon; false resumes them from a
   *     store under the journal directory, so a reconnect or a restart gets a resend of the gap
   * @param reconnect how a refused or dropped session is retried
   */
  public record Fix(
      TlsConfig clientTls, TlsConfig acceptorTls, String username, Secret password, boolean resetOnLogon,
      ReconnectPolicy reconnect) {

    /**
     * The simulator preset: plain loopback, no credentials, sequence numbers reset at every Logon.
     * It is what every recorded smoke and back-test figure was run with.
     */
    public static final Fix SIMULATOR = new Fix(null, null, null, null, true, ReconnectPolicy.DEFAULT);

    /** Validates the settings. */
    public Fix {
      if ((clientTls == null) != (acceptorTls == null)) {
        throw new IllegalArgumentException("TLS needs both the initiator's and the acceptor's settings");
      }
      if ((username == null) != (password == null)) {
        throw new IllegalArgumentException("give a username and a password, or neither");
      }
    }

    /** These settings resuming sequence numbers, or resetting them. */
    public Fix withResetOnLogon(final boolean reset) {
      return new Fix(clientTls, acceptorTls, username, password, reset, reconnect);
    }
  }

  /**
   * Threading, journaling and supervision settings. {@link #DEFAULT} is what the process did before
   * they were configurable (review finding L3); {@link #LOW_LATENCY} spins every thread on the path
   * from socket to socket and keeps the loop thread off file-backed pages (DECISIONS.md DR-R6-L02).
   *
   * @param loopIdleStrategy idle strategy of the loop threads, a name {@link IdleStrategies#of}
   *     accepts
   * @param loopCpu CPU the first group's loop is pinned to on Linux (group {@code g} takes {@code
   *     loopCpu + g}), or -1 for no pinning
   * @param killDeadlineNanos how long a kill waits for the venues to confirm the withdrawal
   *     before it logs out, and again before it closes the sockets
   * @param stallNanos how long a loop or a gateway thread may go without a duty cycle before the
   *     supervisor treats it as stopped
   * @param supervisorPeriodNanos pause between supervisor checks
   * @param gatewayIdleStrategy idle strategy of the D2C, CLOB and feed threads
   * @param gatewayCpu CPU of the D2C session's thread on Linux; the CLOB session takes {@code
   *     gatewayCpu + 1} and the feed {@code gatewayCpu + 2}; -1 for no pinning. The venue
   *     simulators' thread is never pinned: in production the venues are not in the process
   * @param journals how the loops' journals are written; the router journal follows their mode
   * @param conflateCurveInputs hold curve inputs back per instrument and dispatch the newest after
   *     the rest of the inbound ring, one per loop duty cycle (review finding L10, DR-R6-L05)
   */
  public record Tuning(
      String loopIdleStrategy,
      int loopCpu,
      long killDeadlineNanos,
      long stallNanos,
      long supervisorPeriodNanos,
      String gatewayIdleStrategy,
      int gatewayCpu,
      LoopJournals.Settings journals,
      boolean conflateCurveInputs) {

    /**
     * Backoff loop, parking gateway threads, unpinned, direct FNV-1a journals, no conflation,
     * two-second kill deadline, ten-second stall limit, checks every 5 ms.
     */
    public static final Tuning DEFAULT =
        new Tuning("backoff", -1, 2_000_000_000L, 10_000_000_000L, 5_000_000L, "park", -1,
            LoopJournals.Settings.DEFAULT, false);

    /**
     * Busy-spinning loop, gateway and journal threads, ring journals with the WORD64 hash and
     * locked segments, conflated curve inputs. CPUs are the host's to choose ({@link #withLoopThread},
     * {@link #withGatewayThreads}); a spinning thread that is not pinned to a reserved core competes
     * with everything else on the machine.
     */
    public static final Tuning LOW_LATENCY =
        DEFAULT.withLoopThread("busy-spin", -1)
            .withGatewayThreads("busy-spin", -1)
            .withJournals(
                LoopJournals.Settings.DEFAULT
                    .withMode(JournalMode.RING)
                    .withHashAlgorithm(ChainHash.WORD64)
                    .withRing(RingJournal.DEFAULT_RING_CAPACITY, "busy-spin")
                    .withSegmentOptions(SegmentedJournal.Options.DEFAULT.withLockPages(true)))
            .withCurveConflation(true);

    /** Validates the settings. */
    public Tuning {
      IdleStrategies.of(loopIdleStrategy);
      IdleStrategies.of(gatewayIdleStrategy);
      if (killDeadlineNanos <= 0 || stallNanos <= 0 || supervisorPeriodNanos <= 0) {
        throw new IllegalArgumentException("deadlines and periods must be positive");
      }
      if (journals == null) {
        throw new IllegalArgumentException("journal settings are required");
      }
    }

    /** Settings with the default gateway threads, journals and no conflation. */
    public Tuning(
        final String loopIdleStrategy, final int loopCpu, final long killDeadlineNanos, final long stallNanos,
        final long supervisorPeriodNanos) {
      this(loopIdleStrategy, loopCpu, killDeadlineNanos, stallNanos, supervisorPeriodNanos, "park", -1,
          LoopJournals.Settings.DEFAULT, false);
    }

    /** This tuning with another idle strategy and CPU for the loops. */
    public Tuning withLoopThread(final String idleStrategy, final int cpu) {
      return new Tuning(idleStrategy, cpu, killDeadlineNanos, stallNanos, supervisorPeriodNanos, gatewayIdleStrategy,
          gatewayCpu, journals, conflateCurveInputs);
    }

    /** This tuning with other supervision times. */
    public Tuning withSupervision(final long killDeadline, final long stall, final long period) {
      return new Tuning(loopIdleStrategy, loopCpu, killDeadline, stall, period, gatewayIdleStrategy, gatewayCpu,
          journals, conflateCurveInputs);
    }

    /** This tuning with another idle strategy and first CPU for the session and feed threads. */
    public Tuning withGatewayThreads(final String idleStrategy, final int cpu) {
      return new Tuning(loopIdleStrategy, loopCpu, killDeadlineNanos, stallNanos, supervisorPeriodNanos, idleStrategy,
          cpu, journals, conflateCurveInputs);
    }

    /** This tuning with other journal settings. */
    public Tuning withJournals(final LoopJournals.Settings settings) {
      return new Tuning(loopIdleStrategy, loopCpu, killDeadlineNanos, stallNanos, supervisorPeriodNanos,
          gatewayIdleStrategy, gatewayCpu, settings, conflateCurveInputs);
    }

    /** This tuning conflating curve inputs, or not. */
    public Tuning withCurveConflation(final boolean conflate) {
      return new Tuning(loopIdleStrategy, loopCpu, killDeadlineNanos, stallNanos, supervisorPeriodNanos,
          gatewayIdleStrategy, gatewayCpu, journals, conflate);
    }
  }

  /** The gateway threads of a process running on its own threads, by index. */
  public enum GatewayThread {
    /** The D2C session's thread, {@code rates-d2c}. */
    D2C(0, "rates-d2c"),
    /** The CLOB session's thread, {@code rates-clob}. */
    CLOB(1, "rates-clob"),
    /** The book feed's thread, {@code rates-feed}. */
    FEED(2, "rates-feed"),
    /** The simulated venues, curve feed, liquidity and control rings, {@code rates-venues}. */
    VENUES(3, "rates-venues");

    private final int index;
    private final String threadName;

    GatewayThread(final int index, final String threadName) {
      this.index = index;
      this.threadName = threadName;
    }

    /** The thread's slot in the process's arrays. */
    int index() {
      return index;
    }

    /** The thread's name. */
    public String threadName() {
      return threadName;
    }
  }

  /**
   * The answer to a kill.
   *
   * @param confirmed true when nothing of ours is left working at either venue: every cancel was
   *     acknowledged, or the session is down and the venue cancelled on disconnect
   * @param reason null when confirmed, otherwise what is not known to be withdrawn and what was
   *     done about it
   */
  public record KillResult(boolean confirmed, String reason) {

    /** A confirmed withdrawal. */
    public static final KillResult CONFIRMED = new KillResult(true, null);
  }

  /**
   * 2026-11-02T13:25:00Z, 08:25 New York on the fixtures' trading date: five minutes before the
   * 08:30 data release on which the D2C arrival model peaks (240 RFQs an hour above a base of 30,
   * width six minutes). {@link Venues#T0} is 14:30Z, 09:30 New York, whatever its comment says.
   */
  public static final long DEFAULT_START = Venues.T0 - 3_900_000_000_000L;

  /** One instrument group's loop and what it owns. */
  public static final class Group {
    private final String name;
    private final Path dir;
    private final EventLoop loop;
    private final DeskGroup desk;
    private final LoopJournals journals;
    private final SegmentedJournal route;
    /** The router journal's ring in ring mode, which owns {@link #route}; null in direct mode. */
    private final RingJournal routeRing;
    private volatile int liveQuotes;
    private volatile boolean killed;
    private volatile long quotesSent;
    private volatile long fills;
    private volatile long hedgesRouted;
    private volatile int liveChildren;
    // the loop thread's copies of the last values it published
    private int liveQuotesLast;
    private boolean killedLast;
    private long quotesSentLast;
    private long fillsLast;
    private long hedgesRoutedLast;
    private int liveChildrenLast;
    // a one-element array: the count belongs to the loop thread alone, unlike the fields above
    private final long[] statusWrites = new long[1];

    Group(final String name, final Path dir, final EventLoop loop, final DeskGroup desk,
        final LoopJournals journals, final SegmentedJournal route, final RingJournal routeRing) {
      this.name = name;
      this.dir = dir;
      this.loop = loop;
      this.desk = desk;
      this.journals = journals;
      this.route = route;
      this.routeRing = routeRing;
    }

    /** Closes the router journal, through its ring in ring mode. */
    void closeRoute() {
      if (routeRing != null) {
        routeRing.close();
      } else {
        route.close();
      }
    }

    /** The loop's journals, for their counters. */
    public LoopJournals journals() {
      return journals;
    }

    /**
     * Status agent: copies the desk's state into volatile fields other threads read. Each field is
     * written only when its value changed: six volatile stores on every duty cycle cost a store
     * buffer drain apiece on an idle loop for values that almost never move (review finding L11).
     */
    int publishStatus() {
      final int quotes = desk.desk().quoting().liveQuotes();
      if (quotes != liveQuotesLast) {
        liveQuotesLast = quotes;
        liveQuotes = quotes;
        statusWrites[0]++;
      }
      final boolean pulled = !desk.desk().killSwitch().isActive();
      if (pulled != killedLast) {
        killedLast = pulled;
        killed = pulled;
        statusWrites[0]++;
      }
      final long sent = desk.desk().quoting().quotesSent();
      if (sent != quotesSentLast) {
        quotesSentLast = sent;
        quotesSent = sent;
        statusWrites[0]++;
      }
      final long filled = desk.desk().quoting().fills();
      if (filled != fillsLast) {
        fillsLast = filled;
        fills = filled;
        statusWrites[0]++;
      }
      final long hedges = desk.hedgesRouted();
      if (hedges != hedgesRoutedLast) {
        hedgesRoutedLast = hedges;
        hedgesRouted = hedges;
        statusWrites[0]++;
      }
      final int children = desk.liveChildren();
      if (children != liveChildrenLast) {
        liveChildrenLast = children;
        liveChildren = children;
        statusWrites[0]++;
      }
      return 0;
    }

    /** Volatile status writes made so far. Loop thread, or after it stopped. */
    public long statusWrites() {
      return statusWrites[0];
    }

    public String name() {
      return name;
    }

    /** Directory of this group's journals. */
    public Path dir() {
      return dir;
    }

    public EventLoop loop() {
      return loop;
    }

    public DeskGroup desk() {
      return desk;
    }

    public int liveQuotes() {
      return liveQuotes;
    }

    public boolean killed() {
      return killed;
    }

    public long quotesSent() {
      return quotesSent;
    }

    public long fills() {
      return fills;
    }

    public long hedgesRouted() {
      return hedgesRouted;
    }

    public int liveChildren() {
      return liveChildren;
    }
  }

  private final Options options;
  private final UsdUniverse universe;
  private final SimClock clock;
  private final SimulatedVenues venues;
  private final Group[] groups;
  private final VenueSession d2c;
  private final VenueSession clob;
  private final FeedBinding feed;
  private final CurveFeed curve;
  private final ClobLiquidity liquidity;
  private final ManyToOneRingBuffer[] inboundRings;
  private final RingBuffer[] controlRings;
  private final InboundPublisher[] killPublishers;
  private final boolean[] killJournaled;
  private final UnsafeBuffer killBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
  private final Object killLock = new Object();
  private final long[] loopBeat;
  private final long[] loopBeatNanos;
  /** The gateway threads by {@link GatewayThread} ordinal, once running on threads. */
  private final DutyCycleRunner[] gateways = new DutyCycleRunner[GatewayThread.values().length];
  private final long[] gatewayBeatSeen = new long[gateways.length];
  private final long[] gatewayBeatNanos = new long[gateways.length];
  private final AtomicReferenceArray<Runnable> gatewayFaults = new AtomicReferenceArray<>(gateways.length);
  private boolean supervised;
  private volatile Thread supervisor;
  // the halt: set once by any thread, read by the gateway thread every duty cycle
  private volatile String haltReason;
  private volatile boolean haltLogout;
  private volatile long haltNanos;
  private volatile boolean socketsClosed;
  private volatile boolean killPending;
  private volatile int killRequestedBy;
  private final org.agrona.concurrent.MessageHandler controlHandler = this::onControl;
  private final List<LoopRunner> runners = new ArrayList<>();
  private final AtomicBoolean driving = new AtomicBoolean();
  private final Object lifecycle = new Object();
  /** True once {@link #startThreads} has run: the gateways belong to their threads. */
  private volatile boolean threaded;
  private long simLastNanos;
  private double timeScale;
  private long killSwitchMessages;
  private long breachMessages;
  private final Restart restart;
  private long recoveredFrames;
  private long outboundCompleted;
  private boolean feedSyncedSeen;
  private volatile boolean feedSynced;

  /**
   * The router journal's sink, silent while a restart replays decisions it already holds. In ring
   * mode it appends to the journal's ring, so the loop thread does not touch the mapped file.
   */
  private static final class RouteSink implements FrameSink {
    private final SegmentedJournal route;
    private RingJournal ring;
    private boolean muted;

    RouteSink(final SegmentedJournal route, final boolean muted) {
      this.route = route;
      this.muted = muted;
    }

    @Override
    public void append(
        final int templateId, final int schemaVersion, final DirectBuffer buffer, final int offset, final int length) {
      if (muted) {
        return;
      }
      if (ring != null) {
        ring.append(templateId, schemaVersion, buffer, offset, length);
      } else {
        route.append(templateId, schemaVersion, buffer, offset, length);
      }
    }
  }

  /** What the journal directory held at start: nothing, or the journals of a process that stopped. */
  private static final class Restart {
    final boolean[] existing;
    final long[] stateDigest;
    final long[] replayHash;
    final List<String> log = new ArrayList<>();
    long resumeNanos = Long.MIN_VALUE;
    int tornTails;
    boolean any;

    Restart(final Path journalDir, final String[] names) {
      existing = new boolean[names.length];
      stateDigest = new long[names.length];
      replayHash = new long[names.length];
      for (int g = 0; g < names.length; g++) {
        final Path dir = journalDir.resolve(names[g]);
        if (!JournalRecovery.exists(dir, "in")) {
          continue;
        }
        if (!JournalRecovery.exists(dir, "out")) {
          throw new IllegalStateException(
              "group " + names[g] + " has an inbound journal and no outbound journal in " + dir + "; refusing to start");
        }
        existing[g] = true;
        any = true;
        for (final String journal : new String[] {"in", "out", "route"}) {
          if (JournalRecovery.exists(dir, journal)) {
            final JournalRecovery.Report report = JournalRecovery.repair(dir, journal);
            log.add(names[g] + " " + report.describe(journal));
            if (report.tornSegment() != 0) {
              tornTails++;
            }
            if ("in".equals(journal)) {
              resumeNanos = Math.max(resumeNanos, report.lastTimestampNanos());
            }
          }
        }
      }
    }
  }

  private TradingProcess(final Options options) {
    this.options = options;
    this.universe = UsdUniverse.standard();
    final String[] names = {"usd"};
    // Journals already there mean a restart: check and repair them before anything else exists,
    // and start simulated time where the journal ended, not where the day began.
    this.restart = new Restart(options.journalDir(), names);
    this.clock = new SimClock(Math.max(options.startNanos(), restart.resumeNanos));
    final Fix fix = options.fix();
    this.venues = new SimulatedVenues(clock, options.seed(), acceptor(fix, "d2c"), acceptor(fix, "clob"));
    groups = new Group[names.length];
    inboundRings = new ManyToOneRingBuffer[names.length];
    controlRings = new RingBuffer[names.length];
    killPublishers = new InboundPublisher[names.length];
    killJournaled = new boolean[names.length];
    loopBeat = new long[names.length];
    loopBeatNanos = new long[names.length];
    final RingBuffer[] d2cRings = new RingBuffer[names.length];
    final RingBuffer[] clobRings = new RingBuffer[names.length];
    final Int2IntHashMap groupOf = new Int2IntHashMap(-1);
    final RecoveryClock[] recoveryClocks = new RecoveryClock[names.length];
    final RouteSink[] routeSinks = new RouteSink[names.length];
    boolean built = false;
    try {
      // DR-INT-06: run the journals' wait paths (a roll that waits for its segment, an append to a
      // full ring) on scratch journals before any loop starts, so that the first real wait does not
      // deoptimise compiled code on a loop thread while it quotes. A crash during the warm-up
      // leaves its scratch files behind; they are of no use to anyone.
      final Path warmUp = options.journalDir().resolve("journal-warmup");
      deleteTree(warmUp);
      JournalWarmUp.exerciseWaitPaths(warmUp, clock);
      for (int g = 0; g < names.length; g++) {
        final Path dir = options.journalDir().resolve(names[g]);
        Files.createDirectories(dir);
        // A restarted loop first lives in journal time: its clock stands at the first journaled
        // frame and the replayer moves it, then it is switched to simulated time (goLive below).
        final long firstFrame = restart.existing[g] ? LoopRecovery.firstFrameNanos(dir, "in") : -1L;
        recoveryClocks[g] = firstFrame >= 0 ? new RecoveryClock(firstFrame) : null;
        final EventClock loopClock =
            new EventClock(new AnchoredClock(recoveryClocks[g] != null ? recoveryClocks[g] : clock));
        inboundRings[g] = RingBufferContract.newInbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
        killPublishers[g] = new InboundPublisher(inboundRings[g]);
        final OneToOneRingBuffer[] outbound = new OneToOneRingBuffer[AppIds.OUTBOUND_RINGS];
        for (int r = 0; r < outbound.length; r++) {
          outbound[r] = RingBufferContract.newOutbound(RingBufferContract.DEFAULT_OUTBOUND_CAPACITY);
        }
        d2cRings[g] = outbound[AppIds.D2C_GATEWAY];
        clobRings[g] = outbound[AppIds.CLOB_GATEWAY];
        controlRings[g] = outbound[AppIds.CONTROL_GATEWAY];
        // DR-INT-03: the loop's journals use the R2 write path (LoopJournals, segments prepared
        // and touched off the loop thread), so a roll does no I/O on the loop. The mode, the hash
        // of new journals and the journal threads come from the tuning (DR-R6-L03, L04); a journal
        // that exists keeps the hash its files name.
        final LoopJournals.Settings journalSettings = options.tuning().journals();
        final LoopJournals journals =
            restart.existing[g]
                ? LoopJournals.open(dir, "in", "out", SEGMENT_BYTES, loopClock, journalSettings)
                : LoopJournals.create(dir, "in", "out", SEGMENT_BYTES, loopClock, journalSettings);
        final SegmentedJournal.Options routeOptions =
            journalSettings.segmentOptions().withHashAlgorithm(journalSettings.hashAlgorithm());
        SegmentedJournal opened = null;
        RingJournal routeRing = null;
        try {
          opened =
              JournalRecovery.exists(dir, "route")
                  ? SegmentedJournal.open(dir, "route", SEGMENT_BYTES, loopClock, true, routeOptions)
                  : SegmentedJournal.create(dir, "route", SEGMENT_BYTES, loopClock, true, routeOptions);
          if (journalSettings.mode() == JournalMode.RING) {
            routeRing =
                RingJournal.start("route-" + names[g], opened, loopClock, journalSettings.ringCapacity(),
                    IdleStrategies.of(journalSettings.journalIdle()), journalSettings.journalStallNanos());
          }
        } finally {
          if (opened == null) {
            journals.close();
          } else if (journalSettings.mode() == JournalMode.RING && routeRing == null) {
            journals.close();
            opened.close();
          }
        }
        final SegmentedJournal route = opened;
        // The router journal already holds the decisions replay will make again; it is not
        // compared with anything, so they are not written twice. A decision the stopped process
        // made and had not written is therefore missing from it, which is the cost.
        routeSinks[g] = new RouteSink(route, recoveryClocks[g] != null);
        routeSinks[g].ring = routeRing;
        final DeskLoopFactory factory = new DeskLoopFactory(universe, options.settings(), routeSinks[g]);
        Group group = null;
        try {
          final EventLoop.Builder builder =
              EventLoop.builder(loopClock)
                  .inbound(inboundRings[g])
                  .outbound(outbound[AppIds.D2C_GATEWAY])
                  .outbound(outbound[AppIds.CLOB_GATEWAY])
                  .outbound(outbound[AppIds.CONTROL_GATEWAY])
                  // a cancel lost to a full gateway ring leaves a quote or order at the venue:
                  // hold these and deliver them when the gateway has room
                  .retryOnDrop(
                      QuoteCancelEncoder.TEMPLATE_ID,
                      QuoteWithdrawEncoder.TEMPLATE_ID,
                      OrderCancelEncoder.TEMPLATE_ID,
                      OrderWithdrawEncoder.TEMPLATE_ID)
                  .journals(journals);
          if (options.tuning().conflateCurveInputs()) {
            // DR-R6-L05: a curve input is a level; the newest per kind and instrument supersedes
            // the rest, and its rebuild and reprice wait behind the ring's other inputs
            builder.conflate(CurveInputDecoder.TEMPLATE_ID, CURVE_KEY_OFFSET, CURVE_KEY_LENGTH).conflatedPerCycle(1);
          }
          final EventLoop loop = factory.create(builder);
          group = new Group(names[g], dir, loop, factory.last(), journals, route, routeRing);
        } finally {
          if (group == null) {
            journals.close();
            if (routeRing != null) {
              routeRing.close();
            } else {
              route.close();
            }
          }
        }
        groups[g] = group;
        for (final int id : universe.instrumentIds()) {
          groupOf.put(id, g);
        }
      }
      // one router per publishing thread (InboundRouter is single-threaded): the D2C session's
      // also remembers which group each RFQ went to, which a restart rebuilds from the journal
      final InboundRouter gatewayInbound = new InboundRouter(inboundRings, groupOf);
      final InboundRouter clobInbound = new InboundRouter(inboundRings, groupOf);
      final InboundRouter feedInbound = new InboundRouter(inboundRings, groupOf);
      final InboundRouter curveInbound = new InboundRouter(inboundRings, groupOf);
      for (int g = 0; g < names.length; g++) {
        if (recoveryClocks[g] != null) {
          final int group = g;
          final LoopRecovery.Result r =
              LoopRecovery.run(
                  groups[g].loop, recoveryClocks[g], groups[g].dir, "in", "out", groups[g].journals.outbound(),
                  (templateId, buffer, offset, length) -> gatewayInbound.restore(group, buffer, offset, length),
                  LoopRecovery.Observer.NONE);
          restart.stateDigest[g] = groups[g].desk.stateDigest(universe.instrumentIds());
          restart.replayHash[g] = r.replayHash();
          final long down = recoveryClocks[g].goLive(clock);
          routeSinks[g].muted = false;
          recoveredFrames += r.inboundFrames();
          outboundCompleted += r.outboundCompleted();
          restart.log.add(String.format(Locale.ROOT,
              "%s replayed %d inbound frames: %d outbound verified, %d completed, hash %016x; down for %d ms",
              names[g], r.inboundFrames(), r.outboundVerified(), r.outboundCompleted(), r.replayHash(),
              down / 1_000_000L));
        }
        groups[g].loop.addAgent(groups[g]::publishStatus);
      }
      // a fill or reject the loop never received leaves its view of the venue wrong: stop trading
      for (final InboundRouter router : new InboundRouter[] {gatewayInbound, clobInbound, feedInbound, curveInbound}) {
        router.undeliveredHandler(
            (templateId, group) -> requestHalt("a session message could not be delivered to the loop", true));
      }
      final Clock fixClock = fixClock(clock);
      final Path sequences = options.journalDir().resolve("fix");
      final VenueSession.Link link =
          new VenueSession.Link(
              fix.clientTls(), fix.username(), fix.password(), fix.resetOnLogon(),
              fix.resetOnLogon() ? null : sequences.resolve("d2c.seq"), false);
      final Endpoint external = options.d2cEndpoint();
      d2c = new VenueSession(AppIds.D2C_VENUE, AppIds.D2C_GATEWAY,
          new String[] {AppIds.COMP_ID, external == null ? venues.d2c().compId() : external.compId()},
          external == null ? "127.0.0.1" : external.host(), external == null ? venues.d2c().port() : external.port(),
          universe.instrumentMap(), gatewayInbound, fixClock, d2cRings,
          fix.reconnect(), VenueSession.WithdrawPolicy.DEFAULT, link);
      VenueSession second = null;
      try {
        // the CLOB holds orders for us: a session that comes back reconciles them before use
        second = new VenueSession(AppIds.CLOB_VENUE, AppIds.CLOB_GATEWAY,
            new String[] {AppIds.COMP_ID, venues.clob().compId()}, "127.0.0.1", venues.clob().port(),
            universe.instrumentMap(), clobInbound, fixClock, clobRings, fix.reconnect(),
            VenueSession.WithdrawPolicy.DEFAULT,
            link.withSequenceFile(fix.resetOnLogon() ? null : sequences.resolve("clob.seq")).withReconcileOrders(true));
      } finally {
        if (second == null) {
          d2c.close();
        }
      }
      clob = second;
      d2c.inboundDrained(this::inboundDrained);
      clob.inboundDrained(this::inboundDrained);
      feed = new FeedBinding(venues.feed(), venues.clob().feed(), feedInbound, AppIds.CLOB_FEED_STATUS, clock);
      curve = new CurveFeed(universe, venues.d2c(), curveInbound, clock, CURVE_INTERVAL_NANOS);
      liquidity = new ClobLiquidity(universe, venues.d2c(), venues.clob(), clock, LIQUIDITY_INTERVAL_NANOS);
      if (restart.any) {
        // Journaled inputs, so that replay sees them too: the sessions the stopped process had are
        // gone and the venues cancelled on disconnect. The loops pull their quotes and stop
        // counting on their orders; the logons that follow bring the venues back.
        final UnsafeBuffer down = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
        for (final int gateway : new int[] {AppIds.D2C_GATEWAY, AppIds.CLOB_GATEWAY}) {
          final int length =
              ControlMessages.encodeGatewayStatus(
                  down, 0, clock.epochNanos(), gateway, ControlMessages.STATUS_DISCONNECTED);
          gatewayInbound.offer(down, 0, length);
        }
        d2c.recovered();
        clob.recovered();
        feed.requireRebuild();
      }
      built = true;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      if (!built) {
        // a journal refused, a replay that diverged, a store that would not open: release what
        // was built and let the failure through
        closeBuilt();
      }
    }
  }

  /** The constructor failed: release the journals and venues it had built. */
  private void closeBuilt() {
    for (final Group g : groups) {
      if (g != null) {
        g.journals.close();
        g.closeRoute();
      }
    }
    venues.close();
  }

  /** CurveInput conflation key: kind and instrument id, five bytes from body offset 4 (SBE header included). */
  static final int CURVE_KEY_OFFSET =
      io.ratesalgo.messages.sbe.MessageHeaderDecoder.ENCODED_LENGTH + CurveInputDecoder.kindEncodingOffset();

  static final int CURVE_KEY_LENGTH = CurveInputDecoder.kindEncodingLength() + CurveInputDecoder.instrumentIdEncodingLength();

  /**
   * The acceptor settings of one in-process simulator. Each keeps its FIX store and its id
   * sequence under the journal directory, so that a restarted process meets a venue that still
   * knows its sequence numbers and does not hand out execution ids it has used before: the desk
   * drops a fill whose execution id it has already booked.
   */
  private AcceptorOptions acceptor(final Fix fix, final String venue) {
    return new AcceptorOptions(
        fix.acceptorTls(), fix.username(), fix.password(), options.journalDir().resolve("venues").resolve(venue));
  }

  private static void deleteTree(final Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      for (final Path p : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }

  /** True when every loop has read everything in its inbound ring. Any thread. */
  private boolean inboundDrained() {
    for (final ManyToOneRingBuffer ring : inboundRings) {
      if (ring.size() != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * The FIX sessions' clock: intervals from the system's monotonic clock, so session heartbeats
   * keep real time with the venues' FIX engine, and stamps from simulated time.
   */
  private static Clock fixClock(final SimClock sim) {
    return new Clock() {
      @Override
      public long nanoTime() {
        return SystemClock.INSTANCE.nanoTime();
      }

      @Override
      public long epochNanos() {
        return sim.epochNanos();
      }
    };
  }

  /**
   * Builds the process, logs both sessions on and starts the venues' RFQ flow. Time stands at
   * the options' start until it is moved. On a journal directory that already holds journals the
   * process is rebuilt from them first (see the class comment) and time stands where they ended.
   */
  public static TradingProcess start(final Options options) {
    final TradingProcess p = new TradingProcess(options);
    boolean started = false;
    try {
      if (p.restart.any) {
        // let the loops act on the journaled disconnects before any session is back; what they
        // send finds the sessions down and is dropped, as it would be on a live disconnect
        p.pump();
      }
      p.d2c.connect();
      p.clob.connect();
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!(p.d2c.isActive() && p.clob.isActive()
          && (p.options.d2cEndpoint() != null || p.venues.d2c().loggedOn().contains(AppIds.COMP_ID))
          && p.venues.clob().loggedOn().contains(AppIds.COMP_ID))) {
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException("venue sessions did not log on within 20 s");
        }
        p.pump();
        LockSupport.parkNanos(100_000L);
      }
      p.venues.startFlow();
      p.pump();
      started = true;
    } finally {
      if (!started) {
        p.close();
      }
    }
    return p;
  }

  // ---------------------------------------------------------------- single-threaded driving

  /**
   * Moves simulated time forward by {@code nanos} in steps of {@code stepNanos}; after each step
   * the venues run their due events, the curve feed and the liquidity provider publish, and
   * gateways, feed and loops are pumped until the sessions are quiet.
   */
  public void step(final long nanos, final long stepNanos) {
    if (threaded) {
      throw new IllegalStateException("the process is running on its own threads");
    }
    for (long done = 0; done < nanos; done += stepNanos) {
      tick(Math.min(stepNanos, nanos - done));
      pump();
    }
  }

  private void tick(final long nanos) {
    venues.advance(clock, nanos);
    venues.onTime();
    liquidity.doWork();
    curve.doWork();
  }

  /** Pumps gateways, feed, control rings and loops until nothing moves and the sessions agree. */
  public void pump() {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    int quiet = 0;
    while (quiet < 3 && System.nanoTime() < deadline) {
      int work = gatewayWork();
      for (final Group g : groups) {
        work += g.loop.doWork();
      }
      if (work == 0 && inSync()) {
        quiet++;
      } else {
        quiet = 0;
        if (work == 0) {
          LockSupport.parkNanos(20_000L);
        }
      }
    }
  }

  /**
   * One duty cycle of the gateways, the feed and the control rings on the calling thread, and
   * none of the loops: what the gateway thread does while a loop stands still. For a process that
   * is stepped, not one running on its own threads. Tests use it to hold a loop back while the
   * sessions keep working; {@link #pump} is this plus the loops.
   */
  public int driveGateways() {
    if (threaded) {
      throw new IllegalStateException("the process is running on its own threads");
    }
    return gatewayWork();
  }

  /** The stepped process's gateway cycle: every gateway thread's work, in one call. */
  private int gatewayWork() {
    if (haltReason != null) {
      applyHalt();
    }
    for (final GatewayThread t : GatewayThread.values()) {
      runFault(t);
    }
    return d2c.doWork() + clob.doWork() + feedWork() + controlWork();
  }

  private int feedWork() {
    final int work = feed.doWork();
    final boolean synced = feed.synced();
    if (synced != feedSyncedSeen) {
      feedSyncedSeen = synced;
      feedSynced = synced;
    }
    return work;
  }

  private int controlWork() {
    int work = 0;
    for (final RingBuffer ring : controlRings) {
      work += ring.read(controlHandler, 64);
    }
    return work;
  }

  /** Runs a fault injected for thread {@code t}, once; a volatile read when there is none. */
  private void runFault(final GatewayThread t) {
    if (gatewayFaults.get(t.index()) != null) {
      final Runnable fault = gatewayFaults.getAndSet(t.index(), null);
      if (fault != null) {
        fault.run();
      }
    }
  }

  /** One duty cycle of a session's own thread: apply the halt, then the session's work. */
  private int sessionCycle(final GatewayThread t, final VenueSession session) {
    if (haltReason != null) {
      session.halt(haltLogout);
    }
    runFault(t);
    return session.doWork();
  }

  private int feedCycle() {
    runFault(GatewayThread.FEED);
    return feedWork();
  }

  /** The simulator side: moves simulated time, runs the venues, curve feed and liquidity, reads control rings. */
  private int venuesCycle() {
    runFault(GatewayThread.VENUES);
    final long now = System.nanoTime();
    final long simNanos = (long) ((now - simLastNanos) * timeScale);
    if (simNanos >= 1_000_000L) {
      simLastNanos = now;
      tick(simNanos);
    }
    return controlWork();
  }

  /** True when each venue has processed what we sent and we have read what it sent. */
  private boolean inSync() {
    return inSync(d2c, venues.d2c().session(AppIds.COMP_ID)) && inSync(clob, venues.clob().session(AppIds.COMP_ID));
  }

  private static boolean inSync(final VenueSession ours, final quickfix.Session theirs) {
    if (!ours.isActive() || theirs == null) {
      return true;
    }
    // QuickFIX/J's "sender" number is the venue's own next outgoing sequence number
    return theirs.getExpectedSenderNum() == ours.session().expectedInSeq()
        && theirs.getExpectedTargetNum() == ours.session().nextOutSeq();
  }

  private void onControl(final int msgTypeId, final org.agrona.MutableDirectBuffer buffer, final int index, final int length) {
    if (msgTypeId == KillSwitchEncoder.TEMPLATE_ID) {
      killSwitchMessages++;
    } else if (msgTypeId == RiskLimitBreachDecoder.TEMPLATE_ID) {
      breachMessages++;
    }
  }

  // ---------------------------------------------------------------- threaded running

  /**
   * Runs each loop on its own thread, each FIX session and the feed on a thread of its own, and
   * the venues, curve feed and liquidity on a thread that advances simulated time by {@code
   * timeScale} times real time (see the class comment).
   */
  public void startThreads(final double timeScale) {
    synchronized (lifecycle) {
      startThreadsLocked(timeScale);
    }
  }

  private void startThreadsLocked(final double scale) {
    if (threaded) {
      return;
    }
    final Tuning tuning = options.tuning();
    for (final Group g : groups) {
      runners.add(
          LoopRunner.builder(g.loop)
              .name("rates-loop-" + g.name)
              .idleStrategy(IdleStrategies.of(tuning.loopIdleStrategy()))
              .cpu(tuning.loopCpu() < 0 ? -1 : tuning.loopCpu() + runners.size())
              .daemon(true)
              .build()
              .start());
    }
    driving.set(true);
    timeScale = scale;
    simLastNanos = System.nanoTime();
    final int cpu = tuning.gatewayCpu();
    gateways[GatewayThread.D2C.index()] =
        gatewayRunner(GatewayThread.D2C, () -> sessionCycle(GatewayThread.D2C, d2c), cpu < 0 ? -1 : cpu,
            tuning.gatewayIdleStrategy(), cause -> withdrawOnTheWayDown(GatewayThread.D2C, d2c, cause));
    gateways[GatewayThread.CLOB.index()] =
        gatewayRunner(GatewayThread.CLOB, () -> sessionCycle(GatewayThread.CLOB, clob), cpu < 0 ? -1 : cpu + 1,
            tuning.gatewayIdleStrategy(), cause -> withdrawOnTheWayDown(GatewayThread.CLOB, clob, cause));
    gateways[GatewayThread.FEED.index()] =
        gatewayRunner(GatewayThread.FEED, this::feedCycle, cpu < 0 ? -1 : cpu + 2, tuning.gatewayIdleStrategy(),
            cause -> requestHalt(stopped(GatewayThread.FEED, cause), true));
    // the simulators stand in for venues outside the process: never pinned, never spinning
    gateways[GatewayThread.VENUES.index()] =
        gatewayRunner(GatewayThread.VENUES, this::venuesCycle, -1, "park",
            cause -> requestHalt(stopped(GatewayThread.VENUES, cause), true));
    threaded = true;
    for (final DutyCycleRunner r : gateways) {
      r.start();
    }
    final long period = options.tuning().supervisorPeriodNanos();
    final Thread watch =
        new Thread(
            () -> {
              while (driving.get()) {
                try {
                  supervise(System.nanoTime());
                } catch (final RuntimeException e) {
                  // the supervisor must outlive whatever it trips over: close the sockets and go on
                  requestHalt("supervisor check failed: " + e, true);
                  closeSockets();
                }
                LockSupport.parkNanos(period);
              }
            },
            "rates-supervisor");
    watch.setDaemon(true);
    supervisor = watch;
    watch.start();
  }

  private static DutyCycleRunner gatewayRunner(
      final GatewayThread which, final io.ratesalgo.core.Agent work, final int cpu, final String idle,
      final java.util.function.Consumer<Throwable> onFailure) {
    return DutyCycleRunner.builder(which.threadName(), work)
        .idleStrategy(IdleStrategies.of(idle))
        .cpu(cpu)
        .daemon(true)
        .onFailure(onFailure)
        .build();
  }

  private static String stopped(final GatewayThread which, final Throwable cause) {
    return "gateway thread " + which.threadName() + " stopped: " + cause;
  }

  /**
   * A session's thread is ending on a failure. It still owns its session, so it withdraws it:
   * cancels, Logout, and duty cycles on that session only until it is down or the kill deadline
   * passes; then its socket is closed if it is still open, so the venue cancels on disconnect. The
   * other session's thread sees the halt and withdraws its own.
   */
  private void withdrawOnTheWayDown(final GatewayThread which, final VenueSession session, final Throwable cause) {
    requestHalt(stopped(which, cause), true);
    try {
      final long deadline = System.nanoTime() + options.tuning().killDeadlineNanos();
      while (session.haltState() != VenueSession.WITHDRAWN && System.nanoTime() - deadline < 0) {
        session.halt(haltLogout);
        if (session.doWork() == 0) {
          LockSupport.parkNanos(100_000L);
        }
      }
    } catch (final Throwable again) {
      cause.addSuppressed(again);
    } finally {
      if (session.haltState() != VenueSession.WITHDRAWN) {
        closeSocket(session);
      }
    }
  }

  // ---------------------------------------------------------------- halt and supervision

  /**
   * Chaos hook: runs {@code fault} once on the D2C session's thread, at its next duty cycle (on the
   * caller's thread at the next gateway cycle when the process is stepped). A fault that throws
   * kills the thread; one that blocks hangs it. Tests of the supervisor use it; nothing in the
   * process does.
   */
  public void injectGatewayFault(final Runnable fault) {
    injectGatewayFault(GatewayThread.D2C, fault);
  }

  /** {@link #injectGatewayFault(Runnable)} on another gateway thread. */
  public void injectGatewayFault(final GatewayThread thread, final Runnable fault) {
    gatewayFaults.set(thread.index(), fault);
  }

  /** Gateway thread {@code which}, or null before {@link #startThreads}. */
  public DutyCycleRunner gatewayThread(final GatewayThread which) {
    return gateways[which.index()];
  }

  /** The loop runners, one per group, once running on threads. */
  public List<LoopRunner> loopRunners() {
    return List.copyOf(runners);
  }

  /** Latches the halt. Any thread; the first reason is kept, a logout can be added later. */
  private void requestHalt(final String reason, final boolean logout) {
    synchronized (killLock) {
      if (logout) {
        haltLogout = true;
      }
      if (haltReason == null) {
        haltNanos = System.nanoTime();
        haltReason = reason;
      }
    }
  }

  /** The caller's thread, when the process is stepped: applies the halt to both sessions. */
  private void applyHalt() {
    final boolean logout = haltLogout;
    d2c.halt(logout);
    clob.halt(logout);
  }

  /** Last resort, any thread: close both sockets so the venues cancel on disconnect. */
  private void closeSockets() {
    closeSocket(d2c);
    closeSocket(clob);
  }

  /** Last resort, any thread: close one session's socket, the one thing another thread may do to it. */
  private void closeSocket(final VenueSession session) {
    socketsClosed = true;
    session.abort();
  }

  private VenueSession sessionOf(final GatewayThread which) {
    return switch (which) {
      case D2C -> d2c;
      case CLOB -> clob;
      default -> null;
    };
  }

  /** True when both sessions report nothing of ours working at their venue. Any thread. */
  public boolean withdrawn() {
    return d2c.haltState() == VenueSession.WITHDRAWN && clob.haltState() == VenueSession.WITHDRAWN;
  }

  /** Why the process halted, or null if it has not. Any thread. */
  public String haltReason() {
    return haltReason;
  }

  /** True once the supervisor or a kill closed the sockets because nothing else could withdraw. */
  public boolean socketsClosed() {
    return socketsClosed;
  }

  /**
   * One supervisor check at time {@code nowNanos} ({@code System.nanoTime} units). The supervisor
   * thread calls this every few milliseconds while the process runs on its own threads; tests call
   * it with their own times. It reads volatile state and counters only, and acts through the halt
   * flag and, as a last resort, by closing sockets, so it is safe beside the loop and gateway
   * threads. One caller at a time.
   *
   * <ul>
   *   <li>A loop runner that failed or ended, or a loop that made no duty cycle for the stall
   *       limit: halt with logout. If its thread is gone, its inbound ring is emptied so that a
   *       gateway thread waiting for room in it can go on and carry out the halt.
   *   <li>A session thread that ended: halt; it withdrew its session on its way down, and that
   *       session's socket is closed here if it did not manage. A session thread that made no duty
   *       cycle for the stall limit: halt and close its socket at once. The other session's thread
   *       withdraws its own session on the halt.
   *   <li>The feed or venue-simulator thread ended or stalled: halt with logout.
   *   <li>A halt not confirmed within twice the kill deadline: close the sockets.
   *   <li>A KillSwitch message that found the inbound ring full is offered again.
   * </ul>
   */
  public void supervise(final long nowNanos) {
    if (killPending) {
      offerKillMessage();
    }
    if (!threaded) {
      return;
    }
    if (!supervised) {
      supervised = true;
      java.util.Arrays.fill(gatewayBeatNanos, nowNanos);
      java.util.Arrays.fill(loopBeatNanos, nowNanos);
    }
    final long stall = options.tuning().stallNanos();
    for (int g = 0; g < runners.size(); g++) {
      final LoopRunner r = runners.get(g);
      if (r.failure() != null || !r.isRunning()) {
        if (driving.get() || r.failure() != null) {
          requestHalt("loop " + groups[g].name + " stopped: " + r.failure(), true);
        }
        if (!r.isAlive()) {
          // the dead loop was the ring's only reader; taking its place cannot race with it
          inboundRings[g].read(DISCARD, 4096);
        }
        continue;
      }
      final long beat = groups[g].loop.counters().get(LoopCounters.DUTY_CYCLES);
      if (beat != loopBeat[g]) {
        loopBeat[g] = beat;
        loopBeatNanos[g] = nowNanos;
      } else if (nowNanos - loopBeatNanos[g] >= stall) {
        requestHalt("loop " + groups[g].name + " made no duty cycle for " + stall / 1_000_000L + " ms", true);
      }
    }
    for (final GatewayThread which : GatewayThread.values()) {
      superviseGateway(which, nowNanos, stall);
    }
    if (haltReason != null && !socketsClosed && !withdrawn()
        && nowNanos - haltNanos >= 2 * options.tuning().killDeadlineNanos()) {
      closeSockets();
    }
  }

  private void superviseGateway(final GatewayThread which, final long nowNanos, final long stall) {
    final DutyCycleRunner r = gateways[which.index()];
    final VenueSession session = sessionOf(which);
    if (!r.isAlive()) {
      if (driving.get() || r.failure() != null) {
        requestHalt(stopped(which, r.failure()), true);
        if (session != null && session.haltState() != VenueSession.WITHDRAWN) {
          closeSocket(session);
        }
      }
      return;
    }
    final int i = which.index();
    final long beat = r.beats();
    if (beat != gatewayBeatSeen[i]) {
      gatewayBeatSeen[i] = beat;
      gatewayBeatNanos[i] = nowNanos;
    } else if (nowNanos - gatewayBeatNanos[i] >= stall) {
      requestHalt("gateway thread " + which.threadName() + " made no duty cycle for " + stall / 1_000_000L + " ms", true);
      if (session != null) {
        closeSocket(session);
      }
    }
  }

  private static final org.agrona.concurrent.MessageHandler DISCARD = (type, buffer, index, length) -> {};

  /** What stopped a gateway thread or a loop thread, or null. */
  public Throwable failure() {
    for (final DutyCycleRunner r : gateways) {
      if (r != null && r.failure() != null) {
        return r.failure();
      }
    }
    for (final LoopRunner r : runners) {
      if (r.failure() != null) {
        return r.failure();
      }
    }
    return null;
  }

  // ---------------------------------------------------------------- control

  /**
   * The operator's kill switch, on two paths.
   *
   * <p>The direct path comes first and is the one that withdraws: the halt flag is set, the
   * gateway thread reads it on its next duty cycle, stops sending anything from the loops and
   * cancels every quote and order itself. It needs neither a live loop nor room in a ring.
   *
   * <p>The journaled path keeps replay honest: a KillSwitch message (reason Manual, scope 0) is
   * offered to every group's inbound ring and handled on the loop like any input, so replay
   * reproduces the pull (DECISIONS.md DR-R2W-10). If a ring is full the offer is repeated by this
   * call and then by the supervisor; the withdrawal does not wait for it.
   *
   * <p>The call waits up to the kill deadline for both venues to confirm. If they do not, the
   * sessions log out (cancel-on-disconnect) and the call waits once more; if they are still up
   * the sockets are closed. Only a confirmed withdrawal returns {@code confirmed}; the control
   * endpoint answers anything else with 503 and the reason.
   */
  public KillResult kill(final int requestedBy) {
    requestHalt("kill switch, requested by " + requestedBy, false);
    synchronized (killLock) {
      if (!killPending && !allJournaled()) {
        killRequestedBy = requestedBy;
        killPending = true;
      }
    }
    offerKillMessage();
    final long deadlineNanos = options.tuning().killDeadlineNanos();
    if (!threaded) {
      // stepped on the caller's thread, which owns the sessions and the loops
      applyHalt();
      for (final Group g : groups) {
        g.loop.doWork(); // make room in a full inbound ring before the gateways deliver into it
      }
      pump();
      // the pump has let the loops drain their rings: a KillSwitch that found one full fits now
      offerKillMessage();
      pump();
      if (!withdrawn()) {
        requestHalt(haltReason, true);
        applyHalt();
        pump();
      }
    } else if (!await(deadlineNanos)) {
      requestHalt(haltReason, true);
      if (!await(deadlineNanos)) {
        closeSockets();
      }
    }
    if (withdrawn()) {
      awaitLoops(deadlineNanos);
      return KillResult.CONFIRMED;
    }
    return new KillResult(false,
        "withdrawal not confirmed: D2C " + stateText(d2c) + ", CLOB " + stateText(clob)
            + (socketsClosed ? "; sockets closed so the venues cancel on disconnect, confirm at the venues" : ""));
  }

  private static String stateText(final VenueSession s) {
    return switch (s.haltState()) {
      case VenueSession.WITHDRAWN -> "withdrawn";
      case VenueSession.HALTING -> s.unconfirmed() + " unconfirmed";
      default -> "halt not applied";
    };
  }

  private boolean await(final long nanos) {
    final long deadline = System.nanoTime() + nanos;
    while (!withdrawn()) {
      if (System.nanoTime() - deadline >= 0) {
        return false;
      }
      LockSupport.parkNanos(200_000L);
    }
    return true;
  }

  /**
   * After a confirmed kill, gives live loops the same deadline to report their own pull, so the
   * status that follows shows it. Nothing depends on this: the venues have already confirmed.
   */
  private void awaitLoops(final long nanos) {
    if (!threaded || failure() != null) {
      return;
    }
    final long deadline = System.nanoTime() + nanos;
    while (!allPulled() && failure() == null && System.nanoTime() - deadline < 0) {
      LockSupport.parkNanos(200_000L);
    }
  }

  private boolean allJournaled() {
    for (final boolean done : killJournaled) {
      if (!done) {
        return false;
      }
    }
    return true;
  }

  /** Offers the KillSwitch message to every ring that has not taken it yet; never waits. */
  private void offerKillMessage() {
    synchronized (killLock) {
      if (!killPending) {
        return;
      }
      final KillSwitchEncoder e = new KillSwitchEncoder();
      e.wrapAndApplyHeader(killBuffer, 0, new MessageHeaderEncoder())
          .reason(KillReason.Manual)
          .scopeId(0)
          .requestedBy(killRequestedBy)
          .timestampNanos(clock.epochNanos());
      final int length = MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength();
      for (int g = 0; g < killPublishers.length; g++) {
        if (!killJournaled[g]) {
          killJournaled[g] = killPublishers[g].offer(killBuffer, 0, length);
        }
      }
      killPending = !allJournaled();
    }
  }

  /** True once every group's inbound ring has taken the KillSwitch message of a kill. */
  public boolean killMessagePublished() {
    synchronized (killLock) {
      return allJournaled();
    }
  }

  private boolean allPulled() {
    for (final Group g : groups) {
      if (!g.killed || g.liveQuotes != 0) {
        return false;
      }
    }
    return true;
  }

  /** Status as JSON: the shape the deployment rehearsal reads (rates-ops SimulatedQuoter's keys). */
  public String statusJson() {
    int live = 0;
    boolean killed = true;
    long sent = 0;
    long fills = 0;
    long hedges = 0;
    int children = 0;
    for (final Group g : groups) {
      live += g.liveQuotes;
      killed &= g.killed;
      sent += g.quotesSent;
      fills += g.fills;
      hedges += g.hedgesRouted;
      children += g.liveChildren;
    }
    final String halt = haltReason;
    if (halt != null) {
      // after a halt the gateway's count is the one that says what is at the venues; the loops'
      // figures may be stale or frozen
      killed = true;
      live = withdrawn() ? 0 : Math.max(live, d2c.unconfirmed() + clob.unconfirmed());
    }
    return String.format(Locale.ROOT,
        "{\"quoting\":%s,\"killSwitch\":%s,\"liveQuotes\":%d,\"quotesSent\":%d,\"fills\":%d,\"hedgesRouted\":%d,"
            + "\"liveChildOrders\":%d,\"groups\":%d,\"simulatedNanos\":%d,\"halted\":%s,\"withdrawn\":%s,"
            + "\"haltReason\":%s,\"recovered\":%s,\"recoveredFrames\":%d,\"journalTornTails\":%d,"
            + "\"outboundCompleted\":%d,\"reconciling\":%s,\"bookSynced\":%s,\"ready\":%s}",
        !killed, killed, live, sent, fills, hedges, children, groups.length, clock.epochNanos() - options.startNanos(),
        halt != null, halt != null && withdrawn(), halt == null ? "null" : quote(halt), restart.any, recoveredFrames,
        restart.tornTails, outboundCompleted, d2c.isReconciling() || clob.isReconciling(), feedSynced,
        notReadyReason() == null);
  }

  private static String quote(final String text) {
    final StringBuilder b = new StringBuilder(text.length() + 2).append('"');
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '"' || c == '\\') {
        b.append('\\').append(c);
      } else {
        b.append(c < 0x20 ? ' ' : c);
      }
    }
    return b.append('"').toString();
  }

  /** True when the kill switch is engaged: every loop reports it, or the process has halted. */
  public boolean killSwitchEngaged() {
    if (haltReason != null) {
      return true;
    }
    for (final Group g : groups) {
      if (!g.killed) {
        return false;
      }
    }
    return true;
  }

  /** Quotes live at the venues: the loops' count, or after a halt what the gateways still await. */
  public int liveQuotes() {
    int live = 0;
    for (final Group g : groups) {
      live += g.liveQuotes;
    }
    if (haltReason != null) {
      return withdrawn() ? 0 : Math.max(live, d2c.unconfirmed() + clob.unconfirmed());
    }
    return live;
  }

  /**
   * Null when the process may be sent flow, else the reason: it has halted or a thread has
   * failed, a session is not logged on, the CLOB's orders are still being reconciled after a
   * reconnect or a restart, or the CLOB book has no whole snapshot. The loops apply the same
   * conditions to their own quoting through their input health; this is the outside view.
   */
  public String notReadyReason() {
    final String halt = haltReason;
    if (halt != null) {
      return "halted: " + halt;
    }
    if (!d2c.isActive()) {
      return "D2C session not logged on";
    }
    if (!clob.isActive()) {
      return "CLOB session not logged on";
    }
    if (failure() != null) {
      return "stopped: " + failure();
    }
    if (d2c.isReconciling() || clob.isReconciling()) {
      return "order reconciliation with the venue in progress";
    }
    return feedSynced ? null : "CLOB book has no whole snapshot yet";
  }

  // ---------------------------------------------------------------- reads

  public Options options() {
    return options;
  }

  public UsdUniverse universe() {
    return universe;
  }

  public SimClock clock() {
    return clock;
  }

  public SimulatedVenues venues() {
    return venues;
  }

  /** Group {@code g}. */
  public Group group(final int g) {
    return groups[g];
  }

  public int groupCount() {
    return groups.length;
  }

  /** Group {@code g}'s inbound ring (tests publish faults into it). */
  public RingBuffer inboundRing(final int g) {
    return inboundRings[g];
  }

  public VenueSession d2cSession() {
    return d2c;
  }

  public VenueSession clobSession() {
    return clob;
  }

  public FeedBinding feed() {
    return feed;
  }

  public CurveFeed curve() {
    return curve;
  }

  public ClobLiquidity liquidity() {
    return liquidity;
  }

  /** True if the process started on journals a stopped process left and rebuilt itself from them. */
  public boolean recovered() {
    return restart.any;
  }

  /** What the restart found and did, one line per journal and per replayed group; empty on a fresh start. */
  public List<String> recoveryLog() {
    return List.copyOf(restart.log);
  }

  /**
   * Group {@code g}'s {@link DeskGroup#stateDigest} as replay left it at start, before any new
   * input: the state of the stopped process at its last journaled frame. 0 on a fresh start.
   */
  public long recoveredStateDigest(final int g) {
    return restart.stateDigest[g];
  }

  /** The chain hash over the outbound messages group {@code g}'s replay produced at start. */
  public long recoveredReplayHash(final int g) {
    return restart.replayHash[g];
  }

  /** Inbound frames replayed at start, over every group. */
  public long recoveredFrames() {
    return recoveredFrames;
  }

  /** Journals whose torn tail was cut at start. */
  public int journalTornTails() {
    return restart.tornTails;
  }

  /** Outbound frames the stopped process had not journaled, completed at start. */
  public long outboundCompleted() {
    return outboundCompleted;
  }

  /** KillSwitch messages the loops sent on the control ring. */
  public long killSwitchMessages() {
    return killSwitchMessages;
  }

  /** RiskLimitBreach messages the loops sent on the control ring. */
  public long breachMessages() {
    return breachMessages;
  }

  /** Stops the threads, logs out, closes journals and venues. */
  @Override
  public void close() {
    synchronized (lifecycle) {
      closeLocked();
    }
  }

  private void closeLocked() {
    driving.set(false);
    final Thread watch = supervisor;
    try {
      if (watch != null) {
        watch.join(5_000L);
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    for (final DutyCycleRunner r : gateways) {
      if (r != null) {
        r.stop();
      }
    }
    for (final GatewayThread which : new GatewayThread[] {GatewayThread.D2C, GatewayThread.CLOB}) {
      final DutyCycleRunner r = gateways[which.index()];
      if (r != null && r.isAlive()) {
        // the session's thread is stuck and still owns the session: all that is safe is the socket
        closeSocket(sessionOf(which));
      }
    }
    for (final LoopRunner r : runners) {
      r.close();
    }
    for (final GatewayThread which : new GatewayThread[] {GatewayThread.D2C, GatewayThread.CLOB}) {
      final DutyCycleRunner r = gateways[which.index()];
      if (r == null || !r.isAlive()) {
        sessionOf(which).close();
      }
    }
    for (final Group g : groups) {
      if (g != null) {
        g.journals.close();
        g.closeRoute();
      }
    }
    venues.close();
  }
}
