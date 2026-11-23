package io.ratesalgo.app.sim;

import io.ratesalgo.app.gateway.FeedBinding;
import io.ratesalgo.app.gateway.InboundRouter;
import io.ratesalgo.app.gateway.ReconnectPolicy;
import io.ratesalgo.app.gateway.VenueSession;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskLoopFactory;
import io.ratesalgo.app.loop.DeskProfile;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.core.AnchoredClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.LoopJournals;
import io.ratesalgo.core.LoopReplay;
import io.ratesalgo.core.ReplayResult;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.journal.SegmentedJournal;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.OrderCancelEncoder;
import io.ratesalgo.messages.sbe.OrderWithdrawEncoder;
import io.ratesalgo.messages.sbe.QuoteCancelEncoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawEncoder;
import io.ratesalgo.messages.sbe.RiskLimitBreachDecoder;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.venues.clob.ClobSimulator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;

/**
 * The v2 desk profile assembled and driven in steps on the calling thread: the simulator v2
 * venues of one session, a FIX session to each of the four, a book feed per order-book venue, the
 * pricing v2 curve feed, and one USD loop with its journals running a desk built from a {@link
 * DeskProfile}.
 *
 * <p>This is the seam for the trading process, which today constructs the version 1 venues, two
 * sessions and one feed itself. Everything a process needs for the v2 profile is here in the
 * shape the process already has for version 1: {@link #gatewayWork} is what its gateway thread
 * does in one duty cycle, {@link #tick} what its driver does when simulated time moves, {@link
 * #inSync} its quiet test, {@link #loopBuilder} the loop's rings, and {@link #replay} the replay
 * of a group's journals. It has no threads, no supervisor, no halt and no restart on its own
 * journals; those stay the process's.
 *
 * <p>A simulator v2 session opens at 07:00 New York and the clock has to start there. {@link
 * Options#openNanos} is how far into the session the desk logs on: the venues run alone until
 * then, as a market does before a desk connects.
 */
public final class V2DeskProcess implements AutoCloseable {

  /**
   * 2026-11-02T12:00:00Z, 07:00 in New York (EST since 1 November): the open of a simulator v2
   * session on the fixtures' date. The desk's business date and its 17:00 cut are read off this
   * clock, so the session's ten hours end at the cut.
   */
  public static final long SESSION_OPEN_EPOCH_NANOS = 1_793_620_800_000_000_000L;

  /** Time between curve rounds: 500 ms, as the version 1 curve feed. */
  public static final long CURVE_INTERVAL_NANOS = 500_000_000L;

  /** Journal segment size. */
  public static final int SEGMENT_BYTES = 16 << 20;

  private static final String GROUP = "usd";
  private static final String MARKOUTS_START = "markouts.start";
  private static final String MARKOUTS_END = "markouts.end";

  /**
   * What to run.
   *
   * @param journalDir directory for the loop's journals and the markout files
   * @param seed session seed of the market model
   * @param profile the desk profile; its market must be simulator v2. The markout files are set
   *     here, under {@code journalDir}
   * @param openNanos session time at which the desk logs on, nanoseconds after 07:00
   */
  public record Options(Path journalDir, long seed, DeskProfile profile, long openNanos) {

    /** Validates the options. */
    public Options {
      if (profile.market() != DeskProfile.Market.SIM_V2) {
        throw new IllegalArgumentException("the v2 process runs the simulator v2 market, not " + profile.market());
      }
      if (openNanos < 0) {
        throw new IllegalArgumentException("openNanos must be >= 0: " + openNanos);
      }
    }
  }

  private final DeskProfile profile;
  private final UsdUniverse universe;
  private final SimClock clock;
  private final SimulatedVenuesV2 venues;
  private final Path groupDir;
  private final EventLoop loop;
  private final DeskGroup desk;
  private final LoopJournals journals;
  private final SegmentedJournal route;
  private final ManyToOneRingBuffer inboundRing;
  private final RingBuffer controlRing;
  private final InboundPublisher killPublisher;
  private final InboundRouter gatewayInbound;
  private final List<VenueSession> sessions = new ArrayList<>();
  private final List<ClobSimulator> sessionBooks = new ArrayList<>();
  private final List<FeedBinding> feeds = new ArrayList<>();
  private final CurveFeedV2 curve;
  private final UnsafeBuffer killBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
  private final org.agrona.concurrent.MessageHandler controlHandler = this::onControl;
  private final boolean[] feedPaused = new boolean[3];
  private long killSwitchMessages;
  private long breachMessages;
  private boolean sessionEnded;

  private V2DeskProcess(final Options options) {
    final Path dir = options.journalDir();
    this.groupDir = dir.resolve(GROUP);
    this.universe = options.profile().newUniverse();
    this.clock = new SimClock(SESSION_OPEN_EPOCH_NANOS);
    this.venues = new SimulatedVenuesV2(clock, options.seed());
    boolean built = false;
    LoopJournals openedJournals = null;
    SegmentedJournal openedRoute = null;
    try {
      Files.createDirectories(groupDir);
      // The store a session ends with is the one the next begins with. The file the desk reads is
      // not written again while this session's journals can be replayed.
      final Path start = dir.resolve(MARKOUTS_START);
      final Path end = dir.resolve(MARKOUTS_END);
      if (Files.exists(end)) {
        Files.move(end, start, StandardCopyOption.REPLACE_EXISTING);
      }
      this.profile = options.profile().withMarkouts(start, end);

      venues.startFlow();
      venues.runAlone(clock, options.openNanos());

      final EventClock loopClock = new EventClock(new AnchoredClock(clock));
      inboundRing = RingBufferContract.newInbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
      killPublisher = new InboundPublisher(inboundRing);
      final OneToOneRingBuffer[] outbound = new OneToOneRingBuffer[AppIds.V2_OUTBOUND_RINGS];
      for (int r = 0; r < outbound.length; r++) {
        outbound[r] = RingBufferContract.newOutbound(RingBufferContract.DEFAULT_OUTBOUND_CAPACITY);
      }
      controlRing = outbound[AppIds.CONTROL_GATEWAY];
      openedJournals = LoopJournals.create(groupDir, "in", "out", SEGMENT_BYTES, loopClock);
      openedRoute = SegmentedJournal.create(groupDir, "route", SEGMENT_BYTES, loopClock, true);
      journals = openedJournals;
      route = openedRoute;
      final DeskLoopFactory factory = new DeskLoopFactory(profile, universe, route::append);
      loop = factory.create(loopBuilder(loopClock, inboundRing, outbound).journals(journals));
      desk = factory.last();

      final Int2IntHashMap groupOf = new Int2IntHashMap(-1);
      for (final int id : universe.instrumentIds()) {
        groupOf.put(id, 0);
      }
      gatewayInbound = new InboundRouter(new RingBuffer[] {inboundRing}, groupOf);
      final Clock fixClock = fixClock(clock);
      final VenueSession.Link link = new VenueSession.Link(null, null, null, true, null, false);
      sessions.add(
          new VenueSession(
              AppIds.D2C_VENUE, AppIds.D2C_GATEWAY, new String[] {AppIds.COMP_ID, venues.d2c().compId()}, "127.0.0.1",
              venues.d2c().port(), universe.instrumentMap(), gatewayInbound, fixClock,
              new RingBuffer[] {outbound[AppIds.D2C_GATEWAY]}, ReconnectPolicy.DEFAULT,
              VenueSession.WithdrawPolicy.DEFAULT, link));
      sessionBooks.add(null);
      final int[] gateways = {AppIds.CLOB_GATEWAY, AppIds.CLOB2_GATEWAY, AppIds.FUTURES_GATEWAY};
      final int[] venueIds = {AppIds.CLOB_VENUE, AppIds.CLOB2_VENUE, AppIds.FUTURES_VENUE};
      final int[] feedStatus = {AppIds.CLOB_FEED_STATUS, AppIds.CLOB2_FEED_STATUS, AppIds.FUTURES_FEED_STATUS};
      final List<ClobSimulator> books = venues.books();
      if (books.size() != venueIds.length) {
        throw new IllegalStateException("the v2 profile is wired for two Treasury books and a futures book, not " + books.size());
      }
      for (int v = 0; v < books.size(); v++) {
        final ClobSimulator book = books.get(v);
        if (book.venueId() != venueIds[v]) {
          throw new IllegalStateException("venue " + book.compId() + " publishes as " + book.venueId() + ", not " + venueIds[v]);
        }
        // an order book holds orders for us: a session that comes back reconciles them before use
        sessions.add(
            new VenueSession(
                venueIds[v], gateways[v], new String[] {AppIds.COMP_ID, book.compId()}, "127.0.0.1", book.port(),
                universe.instrumentMap(), gatewayInbound, fixClock, new RingBuffer[] {outbound[gateways[v]]},
                ReconnectPolicy.DEFAULT, VenueSession.WithdrawPolicy.DEFAULT, link.withReconcileOrders(true)));
        sessionBooks.add(book);
        feeds.add(new FeedBinding(venues.feed(v), book.feed(), gatewayInbound, feedStatus[v], clock));
      }
      for (final VenueSession s : sessions) {
        s.inboundDrained(() -> inboundRing.size() == 0);
      }
      curve = new CurveFeedV2(universe, venues.session(), gatewayInbound, clock, CURVE_INTERVAL_NANOS);
      built = true;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      if (!built) {
        for (final VenueSession s : sessions) {
          s.close();
        }
        if (openedJournals != null) {
          openedJournals.close();
        }
        if (openedRoute != null) {
          openedRoute.close();
        }
        venues.close();
      }
    }
  }

  /**
   * The loop builder of a v2 desk: the inbound ring, the six outbound rings in gateway-id order
   * (D2C, CLOB, control, an unused one, second CLOB, futures) and the cancels a full ring must
   * not lose. The trading process and a test harness build the loop with the same call.
   */
  public static EventLoop.Builder loopBuilder(
      final EventClock loopClock, final ManyToOneRingBuffer inbound, final OneToOneRingBuffer[] outbound) {
    if (outbound.length != AppIds.V2_OUTBOUND_RINGS) {
      throw new IllegalArgumentException("a v2 loop has " + AppIds.V2_OUTBOUND_RINGS + " outbound rings");
    }
    final EventLoop.Builder builder = EventLoop.builder(loopClock).inbound(inbound);
    for (final OneToOneRingBuffer ring : outbound) {
      builder.outbound(ring);
    }
    return builder.retryOnDrop(
        QuoteCancelEncoder.TEMPLATE_ID,
        QuoteWithdrawEncoder.TEMPLATE_ID,
        OrderCancelEncoder.TEMPLATE_ID,
        OrderWithdrawEncoder.TEMPLATE_ID);
  }

  /** Session heartbeats keep real time with the venues' FIX engine; stamps are simulated time. */
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

  /** Builds the process and logs every session on. Time stands at the options' open until moved. */
  public static V2DeskProcess start(final Options options) {
    final V2DeskProcess p = new V2DeskProcess(options);
    boolean started = false;
    try {
      for (final VenueSession s : p.sessions) {
        s.connect();
      }
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!p.loggedOn()) {
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException("venue sessions did not log on within 20 s");
        }
        p.pump();
        LockSupport.parkNanos(100_000L);
      }
      p.pump();
      started = true;
    } finally {
      if (!started) {
        p.close();
      }
    }
    return p;
  }

  private boolean loggedOn() {
    if (!venues.d2c().loggedOn().contains(AppIds.COMP_ID)) {
      return false;
    }
    for (final ClobSimulator c : venues.books()) {
      if (!c.loggedOn().contains(AppIds.COMP_ID)) {
        return false;
      }
    }
    for (final VenueSession s : sessions) {
      if (!s.isActive()) {
        return false;
      }
    }
    return true;
  }

  /** Moves simulated time by {@code nanos} in steps of {@code stepNanos}, pumping after each. */
  public void step(final long nanos, final long stepNanos) {
    for (long done = 0; done < nanos; done += stepNanos) {
      tick(Math.min(stepNanos, nanos - done));
      pump();
    }
  }

  /** One move of simulated time: the venues run what is due and the curve feed publishes. */
  public void tick(final long nanos) {
    venues.advance(clock, nanos);
    venues.onTime();
    curve.doWork();
  }

  /** Pumps gateways, feeds, the control ring and the loop until nothing moves and the sessions agree. */
  public void pump() {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    int quiet = 0;
    while (quiet < 3 && System.nanoTime() < deadline) {
      final int work = gatewayWork() + loop.doWork();
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

  /** One duty cycle of every session, every feed and the control ring: the gateway thread's work. */
  public int gatewayWork() {
    int work = 0;
    for (final VenueSession s : sessions) {
      work += s.doWork();
    }
    for (int v = 0; v < feeds.size(); v++) {
      if (!feedPaused[v]) {
        work += feeds.get(v).doWork();
      }
    }
    return work + controlRing.read(controlHandler, 64);
  }

  /** True when each venue has processed what we sent and we have read what it sent. */
  public boolean inSync() {
    for (int i = 0; i < sessions.size(); i++) {
      final VenueSession ours = sessions.get(i);
      final quickfix.Session theirs =
          i == 0 ? venues.d2c().session(AppIds.COMP_ID) : sessionBooks.get(i).session(AppIds.COMP_ID);
      if (ours.isActive() && theirs != null
          && (theirs.getExpectedSenderNum() != ours.session().expectedInSeq()
              || theirs.getExpectedTargetNum() != ours.session().nextOutSeq())) {
        return false;
      }
    }
    return true;
  }

  private void onControl(final int msgTypeId, final org.agrona.MutableDirectBuffer buffer, final int index, final int length) {
    if (msgTypeId == KillSwitchEncoder.TEMPLATE_ID) {
      killSwitchMessages++;
    } else if (msgTypeId == RiskLimitBreachDecoder.TEMPLATE_ID) {
      breachMessages++;
    }
  }

  /**
   * Fault injection: stops, or resumes, delivering the book feed of order-book venue {@code v}.
   * The venue keeps publishing and nothing is lost: the frames wait in the channel. To the desk
   * it is a feed that went silent without a gap, which only the book-age limit detects.
   */
  public void pauseFeed(final int v, final boolean paused) {
    feedPaused[v] = paused;
  }

  /** Publishes a manual KillSwitch to the loop as a journaled input; false if its ring is full. */
  public boolean kill(final int requestedBy) {
    final KillSwitchEncoder e = new KillSwitchEncoder();
    e.wrapAndApplyHeader(killBuffer, 0, new MessageHeaderEncoder())
        .reason(KillReason.Manual)
        .scopeId(0)
        .requestedBy(requestedBy)
        .timestampNanos(clock.epochNanos());
    return killPublisher.offer(killBuffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }

  /** Ends the session: the desk saves its client markouts. Idempotent; {@link #close} calls it. */
  public boolean endSession() {
    if (sessionEnded) {
      return false;
    }
    sessionEnded = true;
    return desk.endSession();
  }

  /** Replays a group's journals through a fresh desk of the profile the process ran. */
  public ReplayResult replay() {
    return replay(groupDir, profile);
  }

  /**
   * Replays the journals in {@code groupDir} through a fresh desk built from {@code profile},
   * which must equal the profile that wrote them, markout files included.
   */
  public static ReplayResult replay(final Path groupDir, final DeskProfile profile) {
    return LoopReplay.run(groupDir, "in", "out", new DeskLoopFactory(profile, profile.newUniverse(), FrameSink.NONE));
  }

  public DeskProfile profile() {
    return profile;
  }

  public UsdUniverse universe() {
    return universe;
  }

  public SimClock clock() {
    return clock;
  }

  public SimulatedVenuesV2 venues() {
    return venues;
  }

  /** Directory of the loop's journals. */
  public Path groupDir() {
    return groupDir;
  }

  public EventLoop loop() {
    return loop;
  }

  public DeskGroup desk() {
    return desk;
  }

  public CurveFeedV2 curve() {
    return curve;
  }

  /** The inbound router every session and feed publishes through. */
  public InboundRouter inbound() {
    return gatewayInbound;
  }

  /** The feed binding of order-book venue {@code v}, in the order of {@link SimulatedVenuesV2#books}. */
  public FeedBinding feed(final int v) {
    return feeds.get(v);
  }

  /** The session of venue {@code i}: the D2C venue, then the order books. */
  public VenueSession session(final int i) {
    return sessions.get(i);
  }

  /** KillSwitch messages the loop published on its control ring. */
  public long killSwitchMessages() {
    return killSwitchMessages;
  }

  /**
   * The desk's status as JSON, for an operator: the kill switch, live quotes and children, each
   * order-book venue's health and book age, the curve inputs still seeded, and the net
   * Treasury-to-SOFR spread ladder beside the swap-rate one. Loop thread, or a loop that is
   * stepped by the caller.
   */
  public String statusJson() {
    final long now = clock.epochNanos();
    final StringBuilder sb = new StringBuilder(512);
    sb.append("{\"killSwitch\":").append(!desk.desk().killSwitch().isActive())
        .append(",\"liveQuotes\":").append(desk.desk().quoting().liveQuotes())
        .append(",\"liveChildren\":").append(desk.liveChildren())
        .append(",\"unfedCurveInputs\":").append(desk.pricingV2() == null ? -1 : desk.pricingV2().unfedInputs())
        .append(",\"venues\":[");
    for (int v = 0; v < desk.venueCount(); v++) {
      final int venueId = desk.venueId(v);
      final long seen = desk.venueBookNanos(venueId);
      sb.append(v == 0 ? "" : ",").append("{\"venue\":").append(venueId)
          .append(",\"usable\":").append(desk.venueUsable(venueId))
          .append(",\"bookAgeMillis\":").append(seen == io.ratesalgo.router.BookView.AGE_UNKNOWN ? -1 : (now - seen) / 1_000_000L)
          .append('}');
    }
    sb.append("],\"spreadPv01\":[");
    for (int k = 0; k < io.ratesalgo.api.KeyRateTenors.COUNT; k++) {
      sb.append(k == 0 ? "" : ",")
          .append(desk.spreadRisk() == null ? 0L : Math.round(desk.spreadRisk().spreadPv01(k)));
    }
    return sb.append("]}").toString();
  }

  /** RiskLimitBreach messages the loop published on its control ring. */
  public long breachMessages() {
    return breachMessages;
  }

  /**
   * A replayed desk and the comparison of what it sent with what was recorded.
   *
   * @param result the outbound comparison
   * @param desk the desk the replay built, as it stood after the last journaled frame
   */
  public record Replayed(ReplayResult result, DeskGroup desk) {}

  /**
   * Replays the journals in {@code groupDir} through a fresh desk and hands the desk back: what a
   * restart on these journals starts from.
   */
  public static Replayed replayDesk(final Path groupDir, final DeskProfile profile) {
    final DeskLoopFactory factory = new DeskLoopFactory(profile, profile.newUniverse(), FrameSink.NONE);
    final ReplayResult result = LoopReplay.run(groupDir, "in", "out", factory);
    return new Replayed(result, factory.last());
  }

  @Override
  public void close() {
    try {
      endSession();
    } finally {
      for (final VenueSession s : sessions) {
        s.close();
      }
      journals.close();
      route.close();
      venues.close();
    }
  }
}
