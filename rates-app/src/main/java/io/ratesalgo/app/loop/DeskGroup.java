package io.ratesalgo.app.loop;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.OutboundSink;
import io.ratesalgo.app.market.LoopBook;
import io.ratesalgo.api.SpreadSensitivityProvider;
import io.ratesalgo.app.pricing.AnalyticsPricing;
import io.ratesalgo.app.pricing.FuturesOverlay;
import io.ratesalgo.app.pricing.PricingBinding;
import io.ratesalgo.app.pricing.PricingBindings;
import io.ratesalgo.app.pricing.PricingV2;
import io.ratesalgo.app.pricing.SpreadRiskBook;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.common.time.TimerHandler;
import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.HotPath;
import io.ratesalgo.core.LoopCounters;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.hedge.TableHedgeCostModel;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.HeartbeatDecoder;
import io.ratesalgo.messages.sbe.KillSwitchDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrderCancelRejectDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeBustDecoder;
import io.ratesalgo.messages.sbe.TradePrintDecoder;
import io.ratesalgo.quoting.Desk;
import io.ratesalgo.quoting.QuotingConfig;
import io.ratesalgo.quoting.VenueTickTable;
import io.ratesalgo.router.BookView;
import io.ratesalgo.router.ChildOrderBook;
import io.ratesalgo.router.ExecutionModel;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.router.ParentListener;
import io.ratesalgo.router.ParentOutcome;
import io.ratesalgo.router.RandomSource;
import io.ratesalgo.router.RouterParams;
import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.RouterTimeouts;
import io.ratesalgo.router.SmartOrderRouter;
import io.ratesalgo.router.VenueParams;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongHashSet;

/**
 * One instrument group's desk on its event loop (API_SPEC.md Section 18.4): the pricing binding,
 * the composite book, the P3 desk (kill switch, risk engine, hedgers, quoting engine), one smart
 * order router per hedge instrument, and the handlers and timer that connect them to the loop.
 *
 * <p>Everything here runs on the loop thread, reads time only from {@link EventLoop#clock()} and
 * sends only through {@link EventLoop#publish}, so the same object graph built by the same {@link
 * DeskLoopFactory} in replay produces the same outbound bytes. Periodic work starts on the first
 * dispatched message, not at construction: the live loop is built before its first input and the
 * replay loop on its first frame, so only input-relative deadlines agree between the two.
 *
 * <p>The routers are driven only off a book the desk can trust. The CLOB counts as usable while
 * its session is up and its feed is whole: a GatewayStatus DISCONNECTED for the feed, or a jump
 * in the feed's sequence numbers, marks it gapped until the GatewayStatus CONNECTED that follows a
 * snapshot rebuild. While it is not usable the routers exclude it (their resting children are
 * cancelled, new decisions journal it as Stale) and book updates are not passed to them; the
 * input health is shown the same gap, so quoting stays pulled for as long. A book with no update
 * for {@link #MAX_BOOK_AGE_NANOS} is stale to the routers in the same way.
 *
 * <p>A {@link DeskProfile} says which version of each component is built. With the recorded
 * profile ({@link DeskProfile#defaults}) the desk is what it was before profiles existed, message
 * for message. With {@link DeskProfile.Market#SIM_V2} there are three order-book venues (two
 * Treasury CLOBs and a futures book), each with its own session, feed, sequence, age and
 * reconciliation, and each rule above holds per venue: a venue that is down, gapped or stale is
 * excluded and the others keep trading. A futures venue counts contracts where the desk counts
 * face (API_SPEC.md Section 0); quantities are converted where they enter and leave the loop.
 */
@HotPath
public final class DeskGroup {

  /** One order-book venue as this loop sees it: its session, its feed and the age of its book. */
  private static final class OrderVenue {
    final int venueId;
    final int gatewayId;
    final int feedStatusId;
    final boolean futures;
    final ClobReconciler reconciler = new ClobReconciler();
    boolean sessionUp = true;
    boolean feedWhole = true;
    long seq;
    long bookNanos = BookView.AGE_UNKNOWN;

    OrderVenue(final int venueId, final int gatewayId, final int feedStatusId, final boolean futures) {
      this.venueId = venueId;
      this.gatewayId = gatewayId;
      this.feedStatusId = feedStatusId;
      this.futures = futures;
    }

    boolean usable() {
      return sessionUp && feedWhole;
    }
  }

  private final EventLoop loop;
  private final DeskProfile profile;
  private final DeskSettings settings;
  private final int[] instrumentIds;
  private final int[] futureIds;
  /** What the desk prices with: the binding, under the futures overlay when the universe has futures. */
  private final PricingBinding pricing;
  private final AnalyticsPricing pricingV1;
  private final PricingV2 pricingV2;
  private final SpreadRiskBook spreadRisk;
  private final LoopBook book;
  private final Desk desk;
  private final Hedger hedger;
  private final SmartOrderRouter[] routers;
  private final int[] routerInstrument;
  /** Desk quantity per venue quantity of router {@code j}'s instrument: 1, or a contract's face. */
  private final long[] routerScale;
  private final RouterHedgeSink hedgeSink;
  private final OrderVenue[] venues;
  private final boolean ladder;

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final BookUpdateDecoder bookUpdate = new BookUpdateDecoder();
  private final CurveInputDecoder curveInput = new CurveInputDecoder();
  private final ExecutionReportDecoder execution = new ExecutionReportDecoder();
  private final KillSwitchDecoder killRequest = new KillSwitchDecoder();
  private final TradeBustDecoder tradeBust = new TradeBustDecoder();
  private final OrderCancelRejectDecoder cancelReject = new OrderCancelRejectDecoder();
  private final TradePrintDecoder tradePrint = new TradePrintDecoder();
  private final NewOrderDecoder ownOrder = new NewOrderDecoder();
  private final MessageHeaderDecoder ownHeader = new MessageHeaderDecoder();
  // the quoting side's own resting orders, by ClOrdID: price, negative for a sell
  private final Long2LongHashMap ownOrderPrice = new Long2LongHashMap(64, 0.6f, 0L);
  private final TimerHandler housekeeping = this::onHousekeeping;
  private final ParentListener parentDone = this::onParentDone;

  // parents the routers reported ended, waiting to be passed to the hedger once the report that
  // ended them has been applied (a fill is booked before its parent is closed)
  private final long[] endedParents;
  private int endedCount;

  // executions seen and executions busted today, by venue and execution id
  private final LongHashSet tradesSeen = new LongHashSet(TRADE_SET_CAPACITY);
  private final LongHashSet tradesBusted = new LongHashSet(1_024);

  private boolean started;
  private long lastHedgeNanos;
  private long rfqConfirmations;
  private long venueBusts;
  private long hedgeFills;
  private long unroutedReports;
  private long duplicateBusts;
  private long duplicateFills;
  private long tradePrints;
  private long pendingSinceNanos;
  private long repricesPushed;
  private long ownOrdersRegistered;
  private long unknownBusts;
  private long venueOverfills;
  private long venueOverfillQuantity;
  private long cancelRejects;
  private long hedgesExpired;
  private long hedgesFailed;
  private long hedgesStalled;
  private long feedGaps;

  /**
   * A CLOB book with no update for this long is stale to the routers. The book feed has no
   * heartbeat, so silence is the only sign of a feed that died without a gap; ten seconds is far
   * longer than an active Treasury book goes without a change.
   */
  public static final long MAX_BOOK_AGE_NANOS = 10_000_000_000L;

  private static final int TRADE_SET_CAPACITY = 1 << 16;
  private static final int ROUTER_PARENTS = 16;

  /** Typical futures hedge order, contracts: the instrument directory's typical size. */
  private static final long TYPICAL_FUTURES_CONTRACTS = 50L;

  DeskGroup(final EventLoop loop, final UsdUniverse universe, final DeskSettings settings, final FrameSink routeJournal) {
    this(loop, DeskProfile.defaults(settings), universe, routeJournal);
  }

  DeskGroup(final EventLoop loop, final DeskProfile profile, final UsdUniverse universe, final FrameSink routeJournal) {
    this.loop = loop;
    this.profile = profile;
    this.settings = profile.settings();
    this.instrumentIds = universe.instrumentIds();
    this.futureIds = universe.futureIds();
    this.ladder = profile.router() == DeskProfile.RouterModel.LADDER;
    final PricingBinding binding = PricingBindings.create(profile.pricing(), universe, loop.clock());
    this.pricingV1 = binding instanceof AnalyticsPricing v1 ? v1 : null;
    this.pricingV2 = binding instanceof PricingV2 v2 ? v2 : null;
    this.pricing = futureIds.length == 0 ? binding : new FuturesOverlay(binding, TYPICAL_FUTURES_CONTRACTS);
    this.spreadRisk =
        pricingV2 == null
            ? null
            : new SpreadRiskBook(pricing instanceof SpreadSensitivityProvider p ? p : pricingV2, instrumentIds);

    final boolean simV2 = profile.market() == DeskProfile.Market.SIM_V2;
    venues =
        simV2
            ? new OrderVenue[] {
              new OrderVenue(AppIds.CLOB_VENUE, AppIds.CLOB_GATEWAY, AppIds.CLOB_FEED_STATUS, false),
              new OrderVenue(AppIds.CLOB2_VENUE, AppIds.CLOB2_GATEWAY, AppIds.CLOB2_FEED_STATUS, false),
              new OrderVenue(AppIds.FUTURES_VENUE, AppIds.FUTURES_GATEWAY, AppIds.FUTURES_FEED_STATUS, true)
            }
            : new OrderVenue[] {
              new OrderVenue(AppIds.CLOB_VENUE, AppIds.CLOB_GATEWAY, AppIds.CLOB_FEED_STATUS, false)
            };
    if (!simV2 && futureIds.length != 0) {
      throw new IllegalArgumentException("the standard market has no futures venue for the universe's futures");
    }
    final int[] bookVenues = new int[venues.length];
    for (int v = 0; v < venues.length; v++) {
      bookVenues[v] = venues[v].venueId;
    }
    this.book = new LoopBook(pricing.directory(), bookVenues);
    pricing.book(book);

    final OutboundSink gateway = this::publishToVenue;
    final OutboundSink control =
        (buffer, offset, length) -> loop.publish(AppIds.CONTROL_GATEWAY, buffer, offset, length);

    final int[] bondIds = universe.bondIds();
    final int bonds = bondIds.length;
    final int hedges = bonds + futureIds.length;
    routers = new SmartOrderRouter[hedges];
    routerInstrument = new int[hedges];
    routerScale = new long[hedges];
    final HedgeUniverse hedgeUniverse = new HedgeUniverse();
    final TableHedgeCostModel costs = new TableHedgeCostModel(hedges);
    final RouterParams routerParams = ladder ? RouterPresets.ladder() : RouterPresets.global();
    final ExecutionModel model = ladder ? ExecutionModel.defaults() : null;
    final VenueTickTable ticks = VenueTickTable.simulatorDefaults();
    for (int j = 0; j < hedges; j++) {
      final boolean future = j >= bonds;
      final int id = future ? futureIds[j - bonds] : bondIds[j];
      final UsdUniverse.FutureSpec f = future ? universe.future(id) : null;
      // the recorded desk gave every Treasury the first one's tick; simulator v2 lists each with its own
      final long tick = future ? f.tick() : simV2 ? universe.bond(id).tick() : universe.bondTick();
      final long lot = future ? f.contractMinor() : settings.hedgeLotMinor();
      routerInstrument[j] = id;
      routerScale[j] = future ? f.deskPerVenue() : 1L;
      hedgeUniverse.add(id, lot, settings.hedgeMaxLots());
      // the table cost model's half spread per lot: about half a 1/128 tick on a million of a
      // Treasury (40 USD), half the contract's own tick on a future
      costs.set(id, future ? 0.5 * tick * 1e-9 * (lot / 10_000.0) : 40.0, 0.0, 0.01);
      ticks.instrument(id, tick);
      if (future) {
        book.quantityScale(id, f.deskPerVenue());
      }
      final VenueParams[] routerVenues = routerVenues(future, tick, lot);
      final LoopOrderGateway orders = new LoopOrderGateway(loop, id, routerScale[j]);
      routers[j] =
          new SmartOrderRouter(
              routerParams,
              routerVenues,
              new AgedView(book.view(id, settings.tradeRatePerSecond())),
              orders,
              routeJournal,
              loop.clock(),
              RandomSource.seeded(settings.seed() + id),
              ROUTER_PARENTS,
              64,
              AppIds.ROUTER_BASE + ((long) j << 40),
              RouterTimeouts.DEFAULT.withMaxBookAgeNanos(MAX_BOOK_AGE_NANOS),
              model);
      routers[j].parentListener(parentDone);
      final ChildOrderBook children = routers[j].children();
      orders.childLookup(
          new LoopOrderGateway.ChildLookup() {
            @Override
            public Side side(final long clOrdId) {
              final int slot = children.slotOf(clOrdId);
              return slot < 0 ? Side.Buy : children.side(slot);
            }

            @Override
            public long quantity(final long clOrdId) {
              final int slot = children.slotOf(clOrdId);
              return slot < 0 ? 0L : children.ordered(slot);
            }
          });
    }
    endedParents = new long[hedges * ROUTER_PARENTS];
    hedgeSink = new RouterHedgeSink(routers, routerInstrument, loop.clock(), routerParams.scheduleHorizonNanos());

    final QuotingConfig quoting = settings.quoting();
    if (simV2) {
      // STRATEGY_V2.md Section 10: each instrument's own venue tick, and hedges only in what can be
      // traded now. Neither is a strategy parameter; with the strategy switches off they are unread.
      quoting.strategy().ticks(ticks).hedgeAvailability(this::hedgeAvailable);
    }
    if (profile.markoutLoad() != null && Files.exists(profile.markoutLoad())) {
      quoting.strategy().markoutFile(profile.markoutLoad());
    }
    desk =
        new Desk(
            new Desk.Inputs(
                pricing.directory(),
                pricing,
                pricing,
                book,
                settings.limits(),
                hedgeUniverse,
                costs,
                gateway,
                control,
                hedgeSink,
                loop.clock(),
                quoting,
                settings.tiers(),
                settings.hitRate(),
                settings.hedger(),
                settings.clob()));
    hedger = desk.hedger(CurrencyCode.USD);
    // a parent the hedger gave up waiting on is open risk it no longer counts as hedged
    hedger.stallListener((parentId, instrumentId, remainingMinor, ageNanos) -> hedgesStalled++);
    desk.health().trackVenue(AppIds.D2C_VENUE);
    for (final OrderVenue v : venues) {
      desk.health().trackVenue(v.venueId);
    }
  }

  /** The venues one hedge instrument is routed to: every Treasury CLOB, or the futures book. */
  private VenueParams[] routerVenues(final boolean future, final long tick, final long lot) {
    int n = 0;
    for (final OrderVenue v : venues) {
      if (v.futures == future) {
        n++;
      }
    }
    final VenueParams[] out = new VenueParams[n];
    n = 0;
    for (final OrderVenue v : venues) {
      if (v.futures == future) {
        final VenueParams p = future ? RouterPresets.futures(v.venueId) : RouterPresets.clob(v.venueId);
        out[n++] =
            new VenueParams(p.venueId(), p.kind(), tick, lot, lot, p.takerFee(), p.makerFee(), p.latencyNanos(),
                p.cancelRatePerSecond(), p.adv(), p.participationCap(), p.depthLevels());
      }
    }
    return out;
  }

  private OrderVenue venueOf(final int venueId) {
    for (final OrderVenue v : venues) {
      if (v.venueId == venueId) {
        return v;
      }
    }
    return null;
  }

  private boolean isFuture(final int instrumentId) {
    for (final int id : futureIds) {
      if (id == instrumentId) {
        return true;
      }
    }
    return false;
  }

  /** Desk quantity per venue quantity of an instrument. */
  private long scaleOf(final int instrumentId) {
    for (int j = 0; j < routerInstrument.length; j++) {
      if (routerInstrument[j] == instrumentId) {
        return routerScale[j];
      }
    }
    return 1L;
  }

  /**
   * True when a hedge instrument can be traded now: a venue that lists it has its session up, its
   * book whole and an update within {@link #MAX_BOOK_AGE_NANOS}. The factor hedge planner asks
   * before it plans an instrument, so the future becomes a hedge when the futures book is live
   * and stops being one when it is not.
   */
  public boolean hedgeAvailable(final int instrumentId) {
    final boolean future = isFuture(instrumentId);
    final long now = loop.clock().epochNanos();
    for (final OrderVenue v : venues) {
      if (v.futures == future && v.usable() && v.bookNanos != BookView.AGE_UNKNOWN
          && now - v.bookNanos <= MAX_BOOK_AGE_NANOS) {
        return true;
      }
    }
    return false;
  }

  /**
   * The desk's outbound sink. An order the quoting side rests on an order book is shown to that
   * instrument's router first, so the router neither crosses it nor counts it as liquidity
   * (ladder model; the recorded desk rests none).
   */
  void publishToVenue(final DirectBuffer buffer, final int offset, final int length) {
    if (ladder && RingBufferContract.templateId(buffer, offset) == NewOrderDecoder.TEMPLATE_ID) {
      ownOrder.wrapAndApplyHeader(buffer, offset, ownHeader);
      final long price = ownOrder.price().mantissa();
      for (int j = 0; j < routers.length; j++) {
        if (routerInstrument[j] == ownOrder.instrumentId()
            && routers[j].registerOwnOrder(
                ownOrder.clOrdId(), ownOrder.venueId(), ownOrder.side(), price, ownOrder.quantity())) {
          ownOrderPrice.put(ownOrder.clOrdId(), ownOrder.side() == Side.Buy ? price : -price);
          ownOrdersRegistered++;
        }
      }
    }
    loop.publish(gatewayOf(buffer, offset), buffer, offset, length);
  }

  /** A report on one of the quoting side's own orders: the router's copy follows what is left. */
  private void onOwnOrderReport() {
    final long signed = ownOrderPrice.get(execution.clOrdId());
    if (signed == 0L) {
      return;
    }
    final OrdStatus status = execution.ordStatus();
    final boolean over =
        status == OrdStatus.Filled || status == OrdStatus.Cancelled || status == OrdStatus.Rejected
            || (status == OrdStatus.PartiallyFilled && execution.leavesQuantity() <= 0);
    for (int j = 0; j < routers.length; j++) {
      if (routerInstrument[j] == execution.instrumentId()) {
        if (over) {
          routers[j].removeOwnOrder(execution.clOrdId());
        } else if (status == OrdStatus.PartiallyFilled) {
          routers[j].registerOwnOrder(
              execution.clOrdId(), execution.venueId(), signed > 0 ? Side.Buy : Side.Sell, Math.abs(signed),
              execution.leavesQuantity());
        }
      }
    }
    if (over) {
      ownOrderPrice.remove(execution.clOrdId());
    }
  }

  /** The router's book view with the age of each venue's feed as this loop saw it. */
  private final class AgedView implements BookView {
    private final BookView view;

    AgedView(final BookView view) {
      this.view = view;
    }

    @Override
    public long bestBid(final int venueId) {
      return view.bestBid(venueId);
    }

    @Override
    public long bestAsk(final int venueId) {
      return view.bestAsk(venueId);
    }

    @Override
    public long depth(final int venueId, final Side side, final int level) {
      return view.depth(venueId, side, level);
    }

    @Override
    public long levelPrice(final int venueId, final Side side, final int level) {
      // the ladder model sweeps levels below the touch; the touch model never asks
      return view.levelPrice(venueId, side, level);
    }

    @Override
    public double tradeRatePerSecond(final int venueId) {
      return view.tradeRatePerSecond(venueId);
    }

    @Override
    public long lastUpdateNanos(final int venueId) {
      final OrderVenue v = venueOf(venueId);
      return v == null ? BookView.AGE_UNKNOWN : v.bookNanos;
    }
  }

  private void onParentDone(
      final long parentId, final ParentOutcome outcome, final long quantity, final long filled, final long unresolved) {
    if (outcome == ParentOutcome.Expired) {
      hedgesExpired++;
    } else if (outcome == ParentOutcome.Failed) {
      hedgesFailed++;
    }
    if (endedCount < endedParents.length) {
      endedParents[endedCount++] = parentId;
    }
  }

  /** Passes the parents the routers ended to the hedger, which releases what they did not fill. */
  private void closeEndedParents() {
    for (int i = 0; i < endedCount; i++) {
      hedger.onParentDone(endedParents[i]);
      hedgeSink.done(endedParents[i]);
    }
    endedCount = 0;
  }

  /** Tells every router whether a venue can be used; a router that does not route there ignores it. */
  private void applyVenueHealth(final OrderVenue v) {
    final boolean usable = v.usable();
    for (final SmartOrderRouter router : routers) {
      router.venueHealth(v.venueId, usable);
    }
    closeEndedParents();
  }

  /** Every instrument's fair value to the risk engine, and the spread ladder, after a reprice. */
  private void afterReprice() {
    repricesPushed++;
    desk.risk().onCurveMove(CurrencyCode.USD);
    for (final int id : instrumentIds) {
      final long mid = pricing.fairMid(id);
      if (mid != FairValueSource.NO_VALUE) {
        desk.risk().onFairValue(id, mid);
      }
    }
    spreadRisk.recompute();
  }

  /** Brings the spread book's positions to the risk engine's, which is told of every fill. */
  private void syncSpreadRisk() {
    if (spreadRisk == null) {
      return;
    }
    for (final int id : instrumentIds) {
      final long delta = desk.risk().position(id) - spreadRisk.position(id);
      if (delta != 0) {
        spreadRisk.onFill(id, delta);
      }
    }
  }

  private static long tradeKey(final int venueId, final long execId) {
    return ((long) venueId << 43) ^ execId;
  }

  private static int gatewayOf(final DirectBuffer buffer, final int offset) {
    // Quote, QuoteWithdraw, NewOrder and OrderWithdraw all carry venueId as their first field.
    return AppIds.gatewayFor(buffer.getInt(offset + RingBufferContract.SBE_HEADER_LENGTH, java.nio.ByteOrder.LITTLE_ENDIAN));
  }

  /** Registers the handlers on the loop; called once by the factory. */
  void register() {
    loop.register(HeartbeatDecoder.TEMPLATE_ID, this::onDeskMessage);
    loop.register(QuoteRequestDecoder.TEMPLATE_ID, this::onDeskMessage);
    loop.register(QuoteResponseDecoder.TEMPLATE_ID, this::onDeskMessage);
    loop.register(BookUpdateDecoder.TEMPLATE_ID, this::onBookUpdate);
    loop.register(CurveInputDecoder.TEMPLATE_ID, this::onCurveInput);
    loop.register(ExecutionReportDecoder.TEMPLATE_ID, this::onExecutionReport);
    loop.register(KillSwitchDecoder.TEMPLATE_ID, this::onKillRequest);
    loop.register(TradeBustDecoder.TEMPLATE_ID, this::onTradeBust);
    loop.register(OrderCancelRejectDecoder.TEMPLATE_ID, this::onCancelReject);
    loop.register(TradePrintDecoder.TEMPLATE_ID, this::onTradePrint);
    loop.register(ControlMessages.GATEWAY_STATUS_TEMPLATE_ID, this::onGatewayStatus);
  }

  private void start() {
    if (started) {
      return;
    }
    started = true;
    final long now = loop.clock().epochNanos();
    desk.health().requireHeartbeat(AppIds.CURVE_HEARTBEAT_SOURCE, now);
    desk.risk().startOfDay();
    lastHedgeNanos = now;
    loop.timers().schedule(loop.clock().nanoTime() + settings.housekeepingNanos(), housekeeping);
  }

  private long journalSeq() {
    return loop.counters().get(LoopCounters.INBOUND);
  }

  private void onDeskMessage(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    desk.handler().onMessage(buffer, offset, journalSeq());
    if (templateId == QuoteResponseDecoder.TEMPLATE_ID) {
      syncSpreadRisk();
    }
  }

  private void onBookUpdate(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    bookUpdate.wrapAndApplyHeader(buffer, offset, header);
    final int instrumentId = bookUpdate.instrumentId();
    final int venueId = bookUpdate.venueId();
    book.apply(bookUpdate);
    desk.handler().onMessage(buffer, offset, journalSeq());
    final long now = loop.clock().epochNanos();
    final OrderVenue v = venueOf(venueId);
    if (v != null) {
      v.bookNanos = now;
      final long seq = bookUpdate.venueSeqNo();
      if (v.seq != 0 && seq != v.seq + 1 && v.feedWhole) {
        // the same test the input health applies; whole again only on the feed's CONNECTED
        v.feedWhole = false;
        feedGaps++;
        applyVenueHealth(v);
      }
      v.seq = seq;
    }
    if (!desk.health().healthy(now)) {
      // a sequence gap on the feed: pull now, not at the next timer (the quote-pull-on-gap path)
      desk.quoting().poll(now);
    }
    final long mid = pricing.fairMid(instrumentId);
    if (mid != FairValueSource.NO_VALUE) {
      desk.risk().onFairValue(instrumentId, mid);
    }
    if (v != null && !v.feedWhole) {
      // a gapped book, or one half rebuilt from a snapshot, moves no router's orders
      return;
    }
    for (int j = 0; j < routers.length; j++) {
      if (routerInstrument[j] == instrumentId) {
        routers[j].onBookUpdate(venueId);
      }
    }
    closeEndedParents();
  }

  private void onCurveInput(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    curveInput.wrapAndApplyHeader(buffer, offset, header);
    final CurveInputKind kind = curveInput.kind();
    final int instrumentId = curveInput.instrumentId();
    if (pricingV2 == null) {
      if (kind != CurveInputKind.ParSwap) {
        return;
      }
      if (pricing.onCurveInput(instrumentId, curveInput.value().mantissa(), curveInput.timestampNanos())) {
        desk.risk().onCurveMove(CurrencyCode.USD);
        desk.risk().onFairValue(instrumentId, pricing.fairMid(instrumentId));
      }
      return;
    }
    // version 2 also builds on the overnight fixing and the SOFR futures, and holds inputs until
    // a round is complete: one input moves every fair value, or none
    if (kind != CurveInputKind.ParSwap && kind != CurveInputKind.Fixing && kind != CurveInputKind.Future) {
      return;
    }
    final long before = pricingV2.reprices();
    final boolean held = pricingV2.pendingInputs() > 0;
    pricing.onCurveInput(instrumentId, curveInput.value().mantissa(), curveInput.timestampNanos());
    if (pricingV2.reprices() != before) {
      afterReprice();
    } else if (!held && pricingV2.pendingInputs() > 0) {
      // the first input of a round: the timer applies the round if the rest of it never comes
      pendingSinceNanos = loop.clock().epochNanos();
    }
  }

  private void onTradePrint(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    tradePrint.wrapAndApplyHeader(buffer, offset, header);
    if (!book.applyTrade(tradePrint)) {
      return;
    }
    tradePrints++;
    final int venueId = tradePrint.venueId();
    final OrderVenue v = venueOf(venueId);
    // a print changes no level, but it shows the venue's feed is alive
    v.bookNanos = loop.clock().epochNanos();
    if (!v.feedWhole) {
      return;
    }
    final int instrumentId = tradePrint.instrumentId();
    for (int j = 0; j < routers.length; j++) {
      if (routerInstrument[j] == instrumentId) {
        routers[j].onTrade(
            venueId, tradePrint.aggressorSide(), tradePrint.price().mantissa(), tradePrint.quantity() * routerScale[j]);
      }
    }
    closeEndedParents();
  }

  private void onExecutionReport(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    execution.wrapAndApplyHeader(buffer, offset, header);
    boolean seenBefore = false;
    if (execution.lastQuantity() > 0) {
      seenBefore = !tradesSeen.add(tradeKey(execution.venueId(), execution.execId()));
    }
    if (execution.venueId() == AppIds.D2C_VENUE) {
      // the RFQ venue's fill confirmation repeats the QuoteResponse that booked the trade
      rfqConfirmations++;
      return;
    }
    final long clOrdId = execution.clOrdId();
    if (!AppIds.isRouterOrder(clOrdId)) {
      if (seenBefore) {
        // The same fill again: a venue resends fills after a reconnect, and after a restart the
        // gateway no longer remembers which it has passed on. The quoting engine books a fill
        // each time it is shown one, so the repeat stops here. (The routers drop repeats
        // themselves, by the same venue and execution id.)
        duplicateFills++;
        return;
      }
      if (ladder) {
        onOwnOrderReport();
      }
      desk.handler().onMessage(buffer, offset, journalSeq());
      syncSpreadRisk();
      return;
    }
    final int r = AppIds.routerOf(clOrdId);
    if (r < 0 || r >= routers.length) {
      unroutedReports++;
      return;
    }
    final SmartOrderRouter router = routers[r];
    final long scale = routerScale[r];
    final long child = clOrdId & ~AppIds.CANCEL_FLAG;
    final OrderVenue venue = venueOf(execution.venueId());
    if (venue != null && venue.reconciler.isOpen()) {
      // a line of the venue's answer, whether or not the router still knows the order: one it
      // does not know and the venue still works is an orphan the reconciliation cancels
      venue.reconciler.onReport(child, execution.ordStatus(), execution.cumQuantity() * scale);
    }
    final ChildOrderBook children = router.children();
    final int slot = children.slotOf(child);
    if (slot < 0) {
      unroutedReports++;
      return;
    }
    final long parentId = children.parentId(slot);
    final Side side = children.side(slot);
    router.onExecutionReport(
        execution.venueId(),
        child,
        execution.execId(),
        execution.ordStatus(),
        execution.lastPrice().mantissa(),
        execution.lastQuantity() * scale,
        execution.leavesQuantity() * scale,
        execution.cumQuantity() * scale,
        execution.transactNanos());
    final long fill = children.lastSlot() >= 0 ? children.lastFill() : 0L;
    if (fill > 0) {
      hedgeFills++;
      final long price = children.lastFillPrice();
      if (hedger.remaining(parentId) != 0) {
        hedger.onHedgeFill(parentId, price, fill);
      } else {
        // a fill after the hedger let go of the parent (kill switch): still a position
        desk.risk().onFill(routerInstrument[r], side == Side.Buy ? fill : -fill, price);
      }
      final long excess = children.lastExcess();
      if (excess > 0) {
        // the venue filled more than the child ordered: booked above like any fill, and counted
        // here for the process to alert on
        venueOverfills++;
        venueOverfillQuantity += excess;
      }
      syncSpreadRisk();
    }
    closeEndedParents();
  }

  private void onCancelReject(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    cancelReject.wrapAndApplyHeader(buffer, offset, header);
    cancelRejects++;
    final long request = cancelReject.clOrdId();
    if (!AppIds.isRouterOrder(request)) {
      // one of the quoting engine's own cancels; it keeps its orders by execution report
      return;
    }
    final int r = AppIds.routerOf(request);
    if (r < 0 || r >= routers.length) {
      unroutedReports++;
      return;
    }
    // the child is back where it was before the cancel, with a bounded wait running on it
    routers[r].onCancelReject(cancelReject.venueId(), request & ~AppIds.CANCEL_FLAG);
    closeEndedParents();
  }

  private void onKillRequest(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    killRequest.wrapAndApplyHeader(buffer, offset, header);
    desk.killSwitch().trigger(killRequest.reason(), killRequest.scopeId(), killRequest.requestedBy());
  }

  private void onTradeBust(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    tradeBust.wrapAndApplyHeader(buffer, offset, header);
    final long key = tradeKey(tradeBust.venueId(), tradeBust.execId());
    if (!tradesSeen.contains(key)) {
      // a bust for an execution this desk never booked: reversing it would invent a position
      unknownBusts++;
      return;
    }
    if (!tradesBusted.add(key)) {
      // the same bust again (a resend, a replayed session): the fill is reversed once
      duplicateBusts++;
      return;
    }
    venueBusts++;
    final long qty = tradeBust.quantity() * scaleOf(tradeBust.instrumentId());
    desk.risk().onFill(tradeBust.instrumentId(), tradeBust.side() == Side.Buy ? -qty : qty, tradeBust.price().mantissa());
    syncSpreadRisk();
  }

  private void onGatewayStatus(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    final int gateway = ControlMessages.gatewayStatusGateway(buffer, offset);
    final byte status = ControlMessages.gatewayStatus(buffer, offset);
    final boolean up = status == ControlMessages.STATUS_CONNECTED || status == ControlMessages.STATUS_RECONCILED;
    if (gateway == AppIds.D2C_GATEWAY) {
      desk.health().onSession(AppIds.D2C_VENUE, up);
    } else {
      // a gateway this desk does not trade on matches no venue
      for (final OrderVenue v : venues) {
        if (gateway == v.gatewayId) {
          onVenueSession(v, status, up);
        } else if (gateway == v.feedStatusId) {
          onVenueFeed(v, up);
        }
      }
    }
    desk.quoting().poll(loop.clock().epochNanos());
  }

  /**
   * A session that came back is not usable yet: RECONCILING opens the window in which the venue
   * says what it holds for us, and only RECONCILED, after the routers have settled their children
   * against that, brings the venue up. Quoting stays pulled in between, because the input health
   * counts the session as down.
   */
  private void onVenueSession(final OrderVenue v, final byte status, final boolean up) {
    if (status == ControlMessages.STATUS_RECONCILING) {
      v.reconciler.begin();
    } else if (status == ControlMessages.STATUS_RECONCILED) {
      v.reconciler.finish(v.venueId, routers);
    } else {
      v.reconciler.abandon();
    }
    desk.health().onSession(v.venueId, up);
    v.sessionUp = up;
    applyVenueHealth(v);
  }

  /**
   * The input health learns of a gap only from a jump in sequence numbers. The feed binding
   * announces one before the loop can see it (a frame the ring refused leaves no jump until the
   * next frame), so the jump is shown to it here; on recovery it is seated on the last sequence
   * this loop received, the snapshot's, and the gap cleared.
   */
  private void onVenueFeed(final OrderVenue v, final boolean up) {
    if (up) {
      if (v.seq != 0) {
        desk.health().onBookSequence(v.venueId, v.seq);
      } else {
        // The rebuild delivered no frame (an empty book): there is no sequence to be seated on,
        // and the last one from before the gap may belong to a venue that has restarted and
        // counts from the beginning. The next frame is taken as it comes; the feed binding has
        // already checked that it follows the snapshot.
        desk.health().onFeedRestart(v.venueId);
      }
      desk.health().onFeedRecovered(v.venueId);
      v.bookNanos = loop.clock().epochNanos();
    } else {
      if (v.seq != 0) {
        desk.health().onBookSequence(v.venueId, v.seq + 2);
      }
      if (v.feedWhole && v.seq != 0) {
        // before any frame arrived there was no book to lose: the first snapshot is not a gap
        feedGaps++;
      }
      v.seq = 0; // the snapshot that follows says where the sequence stands
    }
    v.feedWhole = up;
    applyVenueHealth(v);
  }

  private void onHousekeeping(final long timerId, final long nowNanos) {
    final long epoch = loop.clock().epochNanos();
    if (pricingV2 != null) {
      // the business date changes at the cut without market data, and a round the feed left
      // incomplete is applied once it has waited its conflation time on this loop's clock (the
      // binding itself only looks when another input arrives)
      final long before = pricingV2.reprices();
      pricingV2.advanceTo(epoch);
      if (pricingV2.pendingInputs() > 0 && epoch - pendingSinceNanos >= pricingV2.options().conflationNanos()) {
        pricingV2.flush();
      }
      if (pricingV2.reprices() != before) {
        afterReprice();
      }
    }
    desk.quoting().poll(epoch);
    if (epoch - lastHedgeNanos >= settings.hedgeIntervalNanos()) {
      lastHedgeNanos = epoch;
      hedger.onTimer(epoch);
    }
    for (final SmartOrderRouter router : routers) {
      router.poll();
    }
    closeEndedParents();
    loop.timers().schedule(nowNanos + settings.housekeepingNanos(), housekeeping);
  }

  public Desk desk() {
    return desk;
  }

  /**
   * The version 1 pricing binding. A desk built with pricing v2 has none: use {@link #binding}
   * or {@link #pricingV2}.
   */
  public AnalyticsPricing pricing() {
    if (pricingV1 == null) {
      throw new IllegalStateException("this desk prices with version 2; use binding() or pricingV2()");
    }
    return pricingV1;
  }

  /** What the desk prices with, whichever version: fair values, ladders and the directory. */
  public PricingBinding binding() {
    return pricing;
  }

  /** The version 2 pricing binding, or null. */
  public PricingV2 pricingV2() {
    return pricingV2;
  }

  /** The desk's net Treasury-to-SOFR spread ladder (pricing v2), or null. */
  public SpreadRiskBook spreadRisk() {
    return spreadRisk;
  }

  /** The profile this desk was built from. */
  public DeskProfile profile() {
    return profile;
  }

  /**
   * End of the session: writes the client markout store to the profile's save file. Not a loop
   * message and not replayed: it changes nothing the desk does. Call on the loop thread or after
   * the loop stopped. Returns false when the strategy keeps no store or the profile names no file.
   */
  public boolean endSession() {
    if (profile.markoutSave() == null || desk.quoting().markouts() == null) {
      return false;
    }
    try {
      desk.quoting().markouts().save(profile.markoutSave());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return true;
  }

  /**
   * Hedge parents the hedger counts as working that no router holds. The hedger and the routers
   * must agree on what is working, since the reservation price counts working hedges as
   * inventory ({@code Hedger.addWorking}); anything but 0 is a parent one side lost.
   */
  public int orphanWorkingParents() {
    int n = 0;
    for (int i = 0; i < hedger.workingParents(); i++) {
      final long parent = hedger.workingParentId(i);
      boolean held = false;
      for (final SmartOrderRouter router : routers) {
        held |= router.isActive(parent);
      }
      if (!held) {
        n++;
      }
    }
    return n;
  }

  /** Order-book venues this desk trades on. */
  public int venueCount() {
    return venues.length;
  }

  /** Venue id of order-book venue {@code v}. */
  public int venueId(final int v) {
    return venues[v].venueId;
  }

  /** True while a venue's session is up and its book whole: the routers may use it. */
  public boolean venueUsable(final int venueId) {
    final OrderVenue v = venueOf(venueId);
    return v != null && v.usable();
  }

  /** Epoch nanoseconds of a venue's last book update or print, or {@link BookView#AGE_UNKNOWN}. */
  public long venueBookNanos(final int venueId) {
    final OrderVenue v = venueOf(venueId);
    return v == null ? BookView.AGE_UNKNOWN : v.bookNanos;
  }

  /** TradePrints applied to the book and shown to the routers. */
  public long tradePrints() {
    return tradePrints;
  }

  /** Times every fair value was pushed to the risk engine after a version 2 reprice. */
  public long repricesPushed() {
    return repricesPushed;
  }

  /** Orders of the quoting side registered with a router as our own. */
  public long ownOrdersRegistered() {
    return ownOrdersRegistered;
  }

  public LoopBook book() {
    return book;
  }

  /** Router {@code j}, for hedge instrument {@link #routerInstrument(int)}. */
  public SmartOrderRouter router(final int j) {
    return routers[j];
  }

  public int routerCount() {
    return routers.length;
  }

  public int routerInstrument(final int j) {
    return routerInstrument[j];
  }

  /** Parents the hedger handed to the routers. */
  public long hedgesRouted() {
    return hedgeSink.accepted();
  }

  /** Fills applied to router children. */
  public long hedgeFills() {
    return hedgeFills;
  }

  /** RFQ fill confirmations seen (and not booked twice). */
  public long rfqConfirmations() {
    return rfqConfirmations;
  }

  /** Venue TradeBusts applied to the risk position. */
  public long venueBusts() {
    return venueBusts;
  }

  /** TradeBusts ignored because the same execution had already been busted. */
  public long duplicateBusts() {
    return duplicateBusts;
  }

  /** Repeated fills on the quoting engine's own orders, dropped by venue and execution id. */
  public long duplicateFills() {
    return duplicateFills;
  }

  /** Order reconciliations with the CLOB completed after its session came back. */
  public long reconciliations() {
    long n = 0;
    for (final OrderVenue v : venues) {
      n += v.reconciler.runs();
    }
    return n;
  }

  /** Router children a reconciliation found the CLOB did not list; each was sent a cancel. */
  public long reconcileMissing() {
    long n = 0;
    for (final OrderVenue v : venues) {
      n += v.reconciler.missingAtVenue();
    }
    return n;
  }

  /** Router children checked against the CLOB, over every reconciliation. */
  public long reconcileChecked() {
    long n = 0;
    for (final OrderVenue v : venues) {
      n += v.reconciler.checked();
    }
    return n;
  }

  /** Orders the CLOB worked that no router held, cancelled by a reconciliation. */
  public long reconcileOrphans() {
    long n = 0;
    for (final OrderVenue v : venues) {
      n += v.reconciler.orphansCancelled();
    }
    return n;
  }

  /** Quantity reconciliations released for re-routing. */
  public long reconcileReleased() {
    long n = 0;
    for (final OrderVenue v : venues) {
      n += v.reconciler.releasedQuantity();
    }
    return n;
  }

  /** True while a venue's order status is being collected. */
  public boolean reconciling() {
    for (final OrderVenue v : venues) {
      if (v.reconciler.isOpen()) {
        return true;
      }
    }
    return false;
  }

  /** TradeBusts ignored because this desk never booked the execution they name. */
  public long unknownBusts() {
    return unknownBusts;
  }

  /** Fills that took a router child above its ordered quantity: booked, and an alert. */
  public long venueOverfills() {
    return venueOverfills;
  }

  /** Quantity venues filled above what router children ordered. */
  public long venueOverfillQuantity() {
    return venueOverfillQuantity;
  }

  /** OrderCancelRejects received. */
  public long cancelRejects() {
    return cancelRejects;
  }

  /** Hedge parents the routers ended Expired: the remainder could not be placed in time. */
  public long hedgesExpired() {
    return hedgesExpired;
  }

  /** Hedge parents the routers ended Failed: a venue never answered for a child. */
  public long hedgesFailed() {
    return hedgesFailed;
  }

  /** Hedge parents the hedger stopped counting as hedged because they sat unfilled too long. */
  public long hedgesStalled() {
    return hedgesStalled;
  }

  /** Times the CLOB feed was marked gapped. */
  public long feedGaps() {
    return feedGaps;
  }

  /** True while the first CLOB's session is up and its book whole: the routers may use it. */
  public boolean clobUsable() {
    return venues[0].usable();
  }

  /** Execution reports for router ids no router holds. */
  public long unroutedReports() {
    return unroutedReports;
  }

  /** Router children live now, over every router. */
  public int liveChildren() {
    int n = 0;
    for (final SmartOrderRouter router : routers) {
      final ChildOrderBook children = router.children();
      for (int cs = 0; cs < children.highWater(); cs++) {
        if (children.inUse(cs) && ChildOrderBook.isLive(children.state(cs))) {
          n++;
        }
      }
    }
    return n;
  }

  /**
   * A digest of what this desk would trade on: positions and pending quantity per instrument, the
   * quoting engine's counts, the kill switch, the hedger's working parents with what each still
   * has to fill, every router child and whether it is live or finished, the session and feed flags and the number of
   * timers armed. Two desks with the same digest have been through the same inputs; the restart
   * test compares a desk rebuilt from its journals with the desk as it stood. Loop thread, or a
   * loop that is not running.
   */
  public long stateDigest(final int[] instrumentIds) {
    long h = 0x9E3779B97F4A7C15L;
    for (final int id : instrumentIds) {
      h = mix(h, desk.risk().position(id));
      h = mix(h, desk.risk().pendingBuy(id));
      h = mix(h, desk.risk().pendingSell(id));
    }
    h = mix(h, desk.quoting().liveQuotes());
    h = mix(h, desk.quoting().quotesSent());
    h = mix(h, desk.quoting().fills());
    h = mix(h, desk.killSwitch().isActive() ? 1L : 0L);
    h = mix(h, desk.risk().flattenOnly() ? 1L : 0L);
    h = mix(h, hedger.workingParents());
    for (int i = 0; i < hedger.workingParents(); i++) {
      final long parent = hedger.workingParentId(i);
      h = mix(h, parent);
      h = mix(h, hedger.remaining(parent));
    }
    h = mix(h, hedgeSink.accepted());
    h = mix(h, hedgeFills);
    h = mix(h, rfqConfirmations);
    for (final SmartOrderRouter router : routers) {
      final ChildOrderBook children = router.children();
      h = mix(h, children.highWater());
      for (int cs = 0; cs < children.highWater(); cs++) {
        if (children.inUse(cs)) {
          h = mix(h, children.clOrdId(cs));
          h = mix(h, ChildOrderBook.isLive(children.state(cs)) ? 1L : ChildOrderBook.isTerminal(children.state(cs)) ? 2L : 3L);
        }
      }
    }
    for (final OrderVenue v : venues) {
      h = mix(h, (v.sessionUp ? 1L : 0L) | (v.feedWhole ? 2L : 0L) | (started ? 4L : 0L));
      h = mix(h, v.seq);
    }
    if (pricingV2 != null) {
      // what version 2 holds beyond positions: the curve's state shows in every fair value
      h = mix(h, pricingV2.reprices());
      h = mix(h, pricingV2.unfedInputs());
      for (final int id : instrumentIds) {
        h = mix(h, pricing.fairMid(id));
        h = mix(h, spreadRisk.position(id));
      }
    }
    return mix(h, loop.timers().liveCount());
  }

  private static long mix(final long h, final long value) {
    final long x = (h ^ value) * 0x100000001B3L;
    return x ^ (x >>> 29);
  }

  /** The loop this desk runs on. */
  public EventLoop loop() {
    return loop;
  }
}
