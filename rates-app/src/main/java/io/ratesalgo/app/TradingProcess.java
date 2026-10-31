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
import io.ratesalgo.core.LoopJournals;
import io.ratesalgo.core.LoopRunner;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.journal.SegmentedJournal;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
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
  public record Options(Path journalDir, long seed, DeskSettings settings, long startNanos) {

    /** Options starting at {@link #DEFAULT_START}. */
    public Options(final Path journalDir, final long seed, final DeskSettings settings) {
      this(journalDir, seed, settings, DEFAULT_START);
    }
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

    Group(final String name, final Path dir, final EventLoop loop, final DeskGroup desk,
        final LoopJournals journals, final SegmentedJournal route) {
      this.name = name;
      this.dir = dir;
      this.loop = loop;
      this.desk = desk;
      this.journals = journals;
      this.route = route;
    }

    /** Status agent: copies the desk's state into volatile fields other threads read. */
    int publishStatus() {
      liveQuotes = desk.desk().quoting().liveQuotes();
      killed = !desk.desk().killSwitch().isActive();
      quotesSent = desk.desk().quoting().quotesSent();
      fills = desk.desk().quoting().fills();
      hedgesRouted = desk.hedgesRouted();
      liveChildren = desk.liveChildren();
      return 0;
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
  private final InboundRouter controlInbound;
  private final UnsafeBuffer killBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
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
    final RingBuffer[] d2cRings = new RingBuffer[names.length];
    final RingBuffer[] clobRings = new RingBuffer[names.length];
    final Int2IntHashMap groupOf = new Int2IntHashMap(-1);
    try {
      for (int g = 0; g < names.length; g++) {
        final Path dir = options.journalDir().resolve(names[g]);
        Files.createDirectories(dir);
        final EventClock loopClock = new EventClock(new AnchoredClock(clock));
        inboundRings[g] = RingBufferContract.newInbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
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
    controlInbound = new InboundRouter(inboundRings, groupOf);
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
              .idleStrategy(IdleStrategies.of("backoff"))
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
                  if (gatewayWork() == 0) {
                    LockSupport.parkNanos(200_000L);
                  }
                }
              } catch (final Throwable t) {
                driverFailure = t;
              }
            },
            "rates-gateways");
    thread.setDaemon(true);
    driver = thread;
    thread.start();
  }

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
   * The operator's kill switch: a KillSwitch message (reason Manual, scope 0) into every group's
   * inbound ring, journaled and handled on the loop like any input, so replay reproduces the pull
   * (DECISIONS.md DR-R2W-10). When the loops run on their own threads, waits up to two seconds for
   * every group to report the switch engaged and no live quote.
   */
  public void kill(final int requestedBy) {
    final KillSwitchEncoder e = new KillSwitchEncoder();
    e.wrapAndApplyHeader(killBuffer, 0, new MessageHeaderEncoder())
        .reason(KillReason.Manual)
        .scopeId(0)
        .requestedBy(requestedBy)
        .timestampNanos(clock.epochNanos());
    synchronized (controlInbound) {
      controlInbound.offer(killBuffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }
    if (driver != null) {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (System.nanoTime() < deadline && !allPulled()) {
        LockSupport.parkNanos(1_000_000L);
      }
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
    return String.format(Locale.ROOT,
        "{\"quoting\":%s,\"killSwitch\":%s,\"liveQuotes\":%d,\"quotesSent\":%d,\"fills\":%d,\"hedgesRouted\":%d,"
            + "\"liveChildOrders\":%d,\"groups\":%d,\"simulatedNanos\":%d}",
        !killed, killed, live, sent, fills, hedges, children, groups.length, clock.epochNanos() - options.startNanos());
  }

  /** Null when both sessions are logged on, else the reason. */
  public String notReadyReason() {
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
    if (thread != null) {
      try {
        thread.join(5_000L);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    for (final LoopRunner r : runners) {
      r.close();
    }
    d2c.close();
    clob.close();
    for (final Group g : groups) {
      if (g != null) {
        g.journals.close();
        g.route.close();
      }
    }
    venues.close();
  }
}
