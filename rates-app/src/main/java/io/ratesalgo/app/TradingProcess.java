package io.ratesalgo.app;

import io.ratesalgo.app.gateway.FeedBinding;
import io.ratesalgo.app.gateway.InboundRouter;
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
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.core.AnchoredClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.IdleStrategies;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.LoopCounters;
import io.ratesalgo.core.LoopJournals;
import io.ratesalgo.core.LoopRunner;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.journal.JournalWarmUp;
import io.ratesalgo.journal.SegmentedJournal;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.OrderCancelEncoder;
import io.ratesalgo.messages.sbe.OrderWithdrawEncoder;
import io.ratesalgo.messages.sbe.QuoteCancelEncoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawEncoder;
import io.ratesalgo.messages.sbe.RiskLimitBreachDecoder;
import io.ratesalgo.venues.Venues;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
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
 * thread and the gateways, feed and venues on one driver thread that paces simulated time at a
 * multiple of real time; the process main uses it.
 *
 * <p>Safety (review findings L1 and L2). The loops pull their own quotes when they are told to, but
 * a loop that has died, hung or fallen behind cannot, so the withdrawal does not depend on them:
 *
 * <ul>
 *   <li>A halt is a volatile flag the gateway thread reads every duty cycle. Once set, each venue
 *       session lets nothing more from the loops out, cancels every quote and order it knows to be
 *       working, repeats the cancels the venue has not answered and, if asked or if they stay
 *       unanswered, logs out so that the venue's cancel-on-disconnect applies ({@link
 *       VenueSession#halt}). A halt is final for the process.
 *   <li>{@link #kill} sets that flag first and then offers the journaled KillSwitch message, so
 *       replay still sees the kill as an input (DR-R2W-10) while the withdrawal itself needs
 *       neither a live loop nor room in the inbound ring. It answers whether the withdrawal was
 *       confirmed.
 *   <li>A supervisor thread ({@link #supervise}) watches the loop runners and the gateway thread.
 *       A loop that stopped or stalled halts the process with a logout. A gateway thread that died
 *       withdraws as it goes down; if it cannot, or is stuck, the supervisor closes the sockets
 *       from outside, which is the one thing another thread may safely do to a session.
 * </ul>
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
   */
  public record Options(Path journalDir, long seed, DeskSettings settings, long startNanos, Tuning tuning) {

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
      return new Options(journalDir, seed, settings, startNanos, other);
    }
  }

  /**
   * Threading and supervision settings. The defaults are what the process did before they were
   * configurable: loops on the backoff idle strategy, not pinned (review finding L3).
   *
   * @param loopIdleStrategy idle strategy of the loop threads, a name {@link IdleStrategies#of}
   *     accepts
   * @param loopCpu CPU the first group's loop is pinned to on Linux (group {@code g} takes {@code
   *     loopCpu + g}), or -1 for no pinning
   * @param killDeadlineNanos how long a kill waits for the venues to confirm the withdrawal
   *     before it logs out, and again before it closes the sockets
   * @param stallNanos how long a loop or the gateway thread may go without a duty cycle before the
   *     supervisor treats it as stopped
   * @param supervisorPeriodNanos pause between supervisor checks
   */
  public record Tuning(
      String loopIdleStrategy, int loopCpu, long killDeadlineNanos, long stallNanos, long supervisorPeriodNanos) {

    /** Backoff, unpinned, two-second kill deadline, ten-second stall limit, checks every 5 ms. */
    public static final Tuning DEFAULT = new Tuning("backoff", -1, 2_000_000_000L, 10_000_000_000L, 5_000_000L);

    /** Validates the settings. */
    public Tuning {
      IdleStrategies.of(loopIdleStrategy);
      if (killDeadlineNanos <= 0 || stallNanos <= 0 || supervisorPeriodNanos <= 0) {
        throw new IllegalArgumentException("deadlines and periods must be positive");
      }
    }

    /** This tuning with another idle strategy and CPU. */
    public Tuning withLoopThread(final String idleStrategy, final int cpu) {
      return new Tuning(idleStrategy, cpu, killDeadlineNanos, stallNanos, supervisorPeriodNanos);
    }

    /** This tuning with other supervision times. */
    public Tuning withSupervision(final long killDeadline, final long stall, final long period) {
      return new Tuning(loopIdleStrategy, loopCpu, killDeadline, stall, period);
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
        final LoopJournals journals, final SegmentedJournal route) {
      this.name = name;
      this.dir = dir;
      this.loop = loop;
      this.desk = desk;
      this.journals = journals;
      this.route = route;
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
  private final AtomicLong gatewayBeat = new AtomicLong();
  private long gatewayBeatSeen;
  private long gatewayBeatNanos;
  private boolean supervised;
  private volatile Thread supervisor;
  // the halt: set once by any thread, read by the gateway thread every duty cycle
  private volatile String haltReason;
  private volatile boolean haltLogout;
  private volatile long haltNanos;
  private volatile boolean socketsClosed;
  private volatile boolean killPending;
  private volatile Runnable gatewayFault;
  private volatile int killRequestedBy;
  private final org.agrona.concurrent.MessageHandler controlHandler = this::onControl;
  private final List<LoopRunner> runners = new ArrayList<>();
  private final AtomicBoolean driving = new AtomicBoolean();
  private final Object lifecycle = new Object();
  private volatile Thread driver;
  private volatile Throwable driverFailure;
  private long killSwitchMessages;
  private long breachMessages;

  private TradingProcess(final Options options) {
    this.options = options;
    this.universe = UsdUniverse.standard();
    this.clock = new SimClock(options.startNanos());
    this.venues = new SimulatedVenues(clock, options.seed());
    final String[] names = {"usd"};
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
    try {
      // DR-INT-06: run the journals' wait paths (a roll that waits for its segment, an append to a
      // full ring) on scratch journals before any loop starts, so that the first real wait does not
      // deoptimise compiled code on a loop thread while it quotes.
      JournalWarmUp.exerciseWaitPaths(options.journalDir().resolve("journal-warmup"), clock);
      for (int g = 0; g < names.length; g++) {
        final Path dir = options.journalDir().resolve(names[g]);
        Files.createDirectories(dir);
        final EventClock loopClock = new EventClock(new AnchoredClock(clock));
        inboundRings[g] = RingBufferContract.newInbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
        killPublishers[g] = new InboundPublisher(inboundRings[g]);
        final OneToOneRingBuffer[] outbound = new OneToOneRingBuffer[AppIds.OUTBOUND_RINGS];
        for (int r = 0; r < outbound.length; r++) {
          outbound[r] = RingBufferContract.newOutbound(RingBufferContract.DEFAULT_OUTBOUND_CAPACITY);
        }
        d2cRings[g] = outbound[AppIds.D2C_GATEWAY];
        clobRings[g] = outbound[AppIds.CLOB_GATEWAY];
        controlRings[g] = outbound[AppIds.CONTROL_GATEWAY];
        // DR-INT-03: the loop's journals use the R2 write path (LoopJournals, default mode,
        // segments prepared and touched off the loop thread), so a roll does no I/O on the loop.
        final LoopJournals journals = LoopJournals.create(dir, "in", "out", SEGMENT_BYTES, loopClock);
        final SegmentedJournal route = SegmentedJournal.create(dir, "route", SEGMENT_BYTES, loopClock, true);
        final DeskLoopFactory factory =
            new DeskLoopFactory(universe, options.settings(), (t, v, b, o, l) -> route.append(t, v, b, o, l));
        final EventLoop loop =
            factory.create(
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
                    .journals(journals));
        final Group group = new Group(names[g], dir, loop, factory.last(), journals, route);
        loop.addAgent(group::publishStatus);
        groups[g] = group;
        for (final int id : universe.instrumentIds()) {
          groupOf.put(id, g);
        }
      }
    } catch (final IOException e) {
      venues.close();
      throw new UncheckedIOException(e);
    }
    final InboundRouter gatewayInbound = new InboundRouter(inboundRings, groupOf);
    final Clock fixClock = fixClock(clock);
    d2c = new VenueSession(AppIds.D2C_VENUE, AppIds.D2C_GATEWAY, new String[] {AppIds.COMP_ID, venues.d2c().compId()},
        "127.0.0.1", venues.d2c().port(), universe.instrumentMap(), gatewayInbound, fixClock, d2cRings);
    clob = new VenueSession(AppIds.CLOB_VENUE, AppIds.CLOB_GATEWAY, new String[] {AppIds.COMP_ID, venues.clob().compId()},
        "127.0.0.1", venues.clob().port(), universe.instrumentMap(), gatewayInbound, fixClock, clobRings);
    feed = new FeedBinding(venues.feed(), venues.clob().feed(), gatewayInbound, AppIds.CLOB_FEED_STATUS, clock);
    curve = new CurveFeed(universe, venues.d2c(), gatewayInbound, clock, CURVE_INTERVAL_NANOS);
    liquidity = new ClobLiquidity(universe, venues.d2c(), venues.clob(), clock, LIQUIDITY_INTERVAL_NANOS);
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
   * the options' start until it is moved.
   */
  public static TradingProcess start(final Options options) {
    final TradingProcess p = new TradingProcess(options);
    boolean started = false;
    try {
      p.d2c.connect();
      p.clob.connect();
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!(p.d2c.isActive() && p.clob.isActive()
          && p.venues.d2c().loggedOn().contains(AppIds.COMP_ID)
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
    if (driver != null) {
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

  private int gatewayWork() {
    if (haltReason != null) {
      applyHalt();
    }
    final Runnable fault = gatewayFault;
    if (fault != null) {
      gatewayFault = null;
      fault.run();
    }
    int work = d2c.doWork() + clob.doWork() + feed.doWork();
    for (final RingBuffer ring : controlRings) {
      work += ring.read(controlHandler, 64);
    }
    return work;
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
   * Runs each loop on its own thread and the venues, gateways, feed and liquidity on a driver
   * thread that advances simulated time by {@code timeScale} times real time.
   */
  public void startThreads(final double timeScale) {
    synchronized (lifecycle) {
      startThreadsLocked(timeScale);
    }
  }

  private void startThreadsLocked(final double timeScale) {
    if (driver != null) {
      return;
    }
    for (final Group g : groups) {
      runners.add(
          LoopRunner.builder(g.loop)
              .name("rates-loop-" + g.name)
              .idleStrategy(IdleStrategies.of(options.tuning().loopIdleStrategy()))
              .cpu(options.tuning().loopCpu() < 0 ? -1 : options.tuning().loopCpu() + runners.size())
              .daemon(true)
              .build()
              .start());
    }
    driving.set(true);
    final Thread thread =
        new Thread(
            () -> {
              try {
                long last = System.nanoTime();
                while (driving.get()) {
                  final long now = System.nanoTime();
                  final long simNanos = (long) ((now - last) * timeScale);
                  if (simNanos >= 1_000_000L) {
                    last = now;
                    tick(simNanos);
                  }
                  gatewayBeat.lazySet(gatewayBeat.get() + 1);
                  if (gatewayWork() == 0) {
                    LockSupport.parkNanos(200_000L);
                  }
                }
              } catch (final Throwable t) {
                driverFailure = t;
                withdrawOnTheWayDown(t);
              }
            },
            "rates-gateways");
    thread.setDaemon(true);
    driver = thread;
    thread.start();
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

  /**
   * The gateway thread is ending on a failure. It still owns the sessions, so it withdraws itself:
   * cancels, Logout, and duty cycles on the two sessions only until both are down or the kill
   * deadline passes. Whatever is still open then is closed, so the venues cancel on disconnect.
   */
  private void withdrawOnTheWayDown(final Throwable cause) {
    requestHalt("gateway thread stopped: " + cause, true);
    try {
      final long deadline = System.nanoTime() + options.tuning().killDeadlineNanos();
      while (!withdrawn() && System.nanoTime() - deadline < 0) {
        applyHalt();
        if (d2c.doWork() + clob.doWork() == 0) {
          LockSupport.parkNanos(100_000L);
        }
      }
    } catch (final Throwable again) {
      cause.addSuppressed(again);
    } finally {
      if (!withdrawn()) {
        closeSockets();
      }
    }
  }

  // ---------------------------------------------------------------- halt and supervision

  /**
   * Chaos hook: runs {@code fault} once on the thread that drives the gateways, at its next duty
   * cycle. A fault that throws kills the gateway thread; one that blocks hangs it. Tests of the
   * supervisor use it; nothing in the process does.
   */
  public void injectGatewayFault(final Runnable fault) {
    gatewayFault = fault;
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

  /** Gateway thread (or the caller's, when the process is stepped): applies the halt to the sessions. */
  private void applyHalt() {
    final boolean logout = haltLogout;
    d2c.halt(logout);
    clob.halt(logout);
  }

  /** Last resort, any thread: close both sockets so the venues cancel on disconnect. */
  private void closeSockets() {
    socketsClosed = true;
    d2c.abort();
    clob.abort();
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
   *   <li>A gateway thread that ended: halt; it withdraws on its way down, and the sockets are
   *       closed here if it did not manage. A gateway thread that made no duty cycle for the stall
   *       limit: halt and close the sockets at once.
   *   <li>A halt not confirmed within twice the kill deadline: close the sockets.
   *   <li>A KillSwitch message that found the inbound ring full is offered again.
   * </ul>
   */
  public void supervise(final long nowNanos) {
    if (killPending) {
      offerKillMessage();
    }
    final Thread gateway = driver;
    if (gateway == null) {
      return;
    }
    if (!supervised) {
      supervised = true;
      gatewayBeatNanos = nowNanos;
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
    if (!gateway.isAlive()) {
      if (driving.get() || driverFailure != null) {
        requestHalt("gateway thread stopped: " + driverFailure, true);
        if (!withdrawn()) {
          closeSockets();
        }
      }
      return;
    }
    final long beat = gatewayBeat.get();
    if (beat != gatewayBeatSeen) {
      gatewayBeatSeen = beat;
      gatewayBeatNanos = nowNanos;
    } else if (nowNanos - gatewayBeatNanos >= stall) {
      requestHalt("gateway thread made no duty cycle for " + stall / 1_000_000L + " ms", true);
      closeSockets();
    }
    if (haltReason != null && !socketsClosed && !withdrawn()
        && nowNanos - haltNanos >= 2 * options.tuning().killDeadlineNanos()) {
      closeSockets();
    }
  }

  private static final org.agrona.concurrent.MessageHandler DISCARD = (type, buffer, index, length) -> {};

  /** What stopped the driver thread or a loop thread, or null. */
  public Throwable failure() {
    final Throwable driverThread = driverFailure;
    if (driverThread != null) {
      return driverThread;
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
    if (driver == null) {
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
    if (driver == null || failure() != null) {
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
            + "\"haltReason\":%s}",
        !killed, killed, live, sent, fills, hedges, children, groups.length, clock.epochNanos() - options.startNanos(),
        halt != null, halt != null && withdrawn(), halt == null ? "null" : quote(halt));
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

  /** Null when both sessions are logged on and nothing has halted or failed, else the reason. */
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
    return failure() == null ? null : "stopped: " + failure();
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
    final Thread thread = driver;
    final Thread watch = supervisor;
    try {
      if (watch != null) {
        watch.join(5_000L);
      }
      if (thread != null) {
        thread.join(5_000L);
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (thread != null && thread.isAlive()) {
      // the gateway thread is stuck and still owns the sessions: all that is safe is the socket
      closeSockets();
    }
    for (final LoopRunner r : runners) {
      r.close();
    }
    if (thread == null || !thread.isAlive()) {
      d2c.close();
      clob.close();
    }
    for (final Group g : groups) {
      if (g != null) {
        g.journals.close();
        g.route.close();
      }
    }
    venues.close();
  }
}
