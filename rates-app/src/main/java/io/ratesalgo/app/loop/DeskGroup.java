package io.ratesalgo.app.loop;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.OutboundSink;
import io.ratesalgo.app.market.LoopBook;
import io.ratesalgo.app.pricing.AnalyticsPricing;
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
import io.ratesalgo.messages.sbe.OrderCancelRejectDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeBustDecoder;
import io.ratesalgo.quoting.Desk;
import io.ratesalgo.router.BookView;
import io.ratesalgo.router.ChildOrderBook;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.router.ParentListener;
import io.ratesalgo.router.ParentOutcome;
import io.ratesalgo.router.RandomSource;
import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.RouterTimeouts;
import io.ratesalgo.router.SmartOrderRouter;
import io.ratesalgo.router.VenueParams;
import io.ratesalgo.messages.sbe.VenueKind;
import org.agrona.DirectBuffer;
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
 */
@HotPath
public final class DeskGroup {

  private final EventLoop loop;
  private final DeskSettings settings;
  private final AnalyticsPricing pricing;
  private final LoopBook book;
  private final Desk desk;
  private final Hedger hedger;
  private final SmartOrderRouter[] routers;
  private final int[] routerInstrument;
  private final RouterHedgeSink hedgeSink;

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final BookUpdateDecoder bookUpdate = new BookUpdateDecoder();
  private final CurveInputDecoder curveInput = new CurveInputDecoder();
  private final ExecutionReportDecoder execution = new ExecutionReportDecoder();
  private final KillSwitchDecoder killRequest = new KillSwitchDecoder();
  private final TradeBustDecoder tradeBust = new TradeBustDecoder();
  private final OrderCancelRejectDecoder cancelReject = new OrderCancelRejectDecoder();
  private final TimerHandler housekeeping = this::onHousekeeping;
  private final ParentListener parentDone = this::onParentDone;

  // parents the routers reported ended, waiting to be passed to the hedger once the report that
  // ended them has been applied (a fill is booked before its parent is closed)
  private final long[] endedParents;
  private int endedCount;

  // executions seen and executions busted today, by venue and execution id
  private final LongHashSet tradesSeen = new LongHashSet(TRADE_SET_CAPACITY);
  private final LongHashSet tradesBusted = new LongHashSet(1_024);

  private boolean clobSessionUp = true;
  private boolean clobFeedWhole = true;
  private long clobSeq;
  private long clobBookNanos = BookView.AGE_UNKNOWN;

  private boolean started;
  private long lastHedgeNanos;
  private long rfqConfirmations;
  private long venueBusts;
  private long hedgeFills;
  private long unroutedReports;
  private long duplicateBusts;
  private long unknownBusts;
  private long venueOverfills;
  private long venueOverfillQuantity;
  private long cancelRejects;
  private long hedgesExpired;
  private long hedgesFailed;
  private long feedGaps;

  /**
   * A CLOB book with no update for this long is stale to the routers. The book feed has no
   * heartbeat, so silence is the only sign of a feed that died without a gap; ten seconds is far
   * longer than an active Treasury book goes without a change.
   */
  public static final long MAX_BOOK_AGE_NANOS = 10_000_000_000L;

  private static final int TRADE_SET_CAPACITY = 1 << 16;
  private static final int ROUTER_PARENTS = 16;

  DeskGroup(final EventLoop loop, final UsdUniverse universe, final DeskSettings settings, final FrameSink routeJournal) {
    this.loop = loop;
    this.settings = settings;
    this.pricing = new AnalyticsPricing(universe, FairValueSource.NO_VALUE);
    this.book = new LoopBook(pricing.directory(), new int[] {AppIds.CLOB_VENUE});
    pricing.book(book);

    final OutboundSink gateway =
        (buffer, offset, length) -> loop.publish(gatewayOf(buffer, offset), buffer, offset, length);
    final OutboundSink control =
        (buffer, offset, length) -> loop.publish(AppIds.CONTROL_GATEWAY, buffer, offset, length);

    final int[] bondIds = universe.bondIds();
    final int bonds = bondIds.length;
    routers = new SmartOrderRouter[bonds];
    routerInstrument = new int[bonds];
    final HedgeUniverse hedgeUniverse = new HedgeUniverse();
    final TableHedgeCostModel costs = new TableHedgeCostModel(bonds);
    final VenueParams[] venues = {clobVenue(universe.bondTick(), settings.hedgeLotMinor())};
    for (int j = 0; j < bonds; j++) {
      final int id = bondIds[j];
      routerInstrument[j] = id;
      hedgeUniverse.add(id, settings.hedgeLotMinor(), settings.hedgeMaxLots());
      costs.set(id, 40.0, 0.0, 0.01);
      final LoopOrderGateway orders = new LoopOrderGateway(loop, id);
      routers[j] =
          new SmartOrderRouter(
              RouterPresets.global(),
              venues,
              new AgedView(book.view(id, settings.tradeRatePerSecond())),
              orders,
              routeJournal,
              loop.clock(),
              RandomSource.seeded(settings.seed() + id),
              ROUTER_PARENTS,
              64,
              AppIds.ROUTER_BASE + ((long) j << 40),
              RouterTimeouts.DEFAULT.withMaxBookAgeNanos(MAX_BOOK_AGE_NANOS));
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
    endedParents = new long[bonds * ROUTER_PARENTS];
    hedgeSink = new RouterHedgeSink(routers, routerInstrument, loop.clock(), RouterPresets.global().scheduleHorizonNanos());

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
                settings.quoting(),
                settings.tiers(),
                settings.hitRate(),
                settings.hedger(),
                settings.clob()));
    hedger = desk.hedger(CurrencyCode.USD);
    desk.health().trackVenue(AppIds.D2C_VENUE);
    desk.health().trackVenue(AppIds.CLOB_VENUE);
  }

  /** The router's book view with the age of the CLOB feed as this loop saw it. */
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
    public double tradeRatePerSecond(final int venueId) {
      return view.tradeRatePerSecond(venueId);
    }

    @Override
    public long lastUpdateNanos(final int venueId) {
      return venueId == AppIds.CLOB_VENUE ? clobBookNanos : BookView.AGE_UNKNOWN;
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

  /** Tells every router whether the CLOB can be used. */
  private void applyClobHealth() {
    final boolean usable = clobSessionUp && clobFeedWhole;
    for (final SmartOrderRouter router : routers) {
      router.venueHealth(AppIds.CLOB_VENUE, usable);
    }
    closeEndedParents();
  }

  private static long tradeKey(final int venueId, final long execId) {
    return ((long) venueId << 43) ^ execId;
  }

  private static VenueParams clobVenue(final long tick, final long lot) {
    final VenueParams p = RouterPresets.clob(AppIds.CLOB_VENUE);
    return new VenueParams(p.venueId(), VenueKind.Clob, tick, lot, lot, p.takerFee(), p.makerFee(), p.latencyNanos(),
        p.cancelRatePerSecond(), p.adv(), p.participationCap(), p.depthLevels());
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
  }

  private void onBookUpdate(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    bookUpdate.wrapAndApplyHeader(buffer, offset, header);
    final int instrumentId = bookUpdate.instrumentId();
    final int venueId = bookUpdate.venueId();
    book.apply(bookUpdate);
    desk.handler().onMessage(buffer, offset, journalSeq());
    final long now = loop.clock().epochNanos();
    if (venueId == AppIds.CLOB_VENUE) {
      clobBookNanos = now;
      final long seq = bookUpdate.venueSeqNo();
      if (clobSeq != 0 && seq != clobSeq + 1 && clobFeedWhole) {
        // the same test the input health applies; whole again only on the feed's CONNECTED
        clobFeedWhole = false;
        feedGaps++;
        applyClobHealth();
      }
      clobSeq = seq;
    }
    if (!desk.health().healthy(now)) {
      // a sequence gap on the feed: pull now, not at the next timer (the quote-pull-on-gap path)
      desk.quoting().poll(now);
    }
    final long mid = pricing.fairMid(instrumentId);
    if (mid != FairValueSource.NO_VALUE) {
      desk.risk().onFairValue(instrumentId, mid);
    }
    if (venueId == AppIds.CLOB_VENUE && !clobFeedWhole) {
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
    if (curveInput.kind() != CurveInputKind.ParSwap) {
      return;
    }
    final int instrumentId = curveInput.instrumentId();
    if (pricing.onCurveInput(instrumentId, curveInput.value().mantissa(), curveInput.timestampNanos())) {
      desk.risk().onCurveMove(CurrencyCode.USD);
      desk.risk().onFairValue(instrumentId, pricing.fairMid(instrumentId));
    }
  }

  private void onExecutionReport(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    execution.wrapAndApplyHeader(buffer, offset, header);
    if (execution.lastQuantity() > 0) {
      tradesSeen.add(tradeKey(execution.venueId(), execution.execId()));
    }
    if (execution.venueId() == AppIds.D2C_VENUE) {
      // the RFQ venue's fill confirmation repeats the QuoteResponse that booked the trade
      rfqConfirmations++;
      return;
    }
    final long clOrdId = execution.clOrdId();
    if (!AppIds.isRouterOrder(clOrdId)) {
      desk.handler().onMessage(buffer, offset, journalSeq());
      return;
    }
    final int r = AppIds.routerOf(clOrdId);
    if (r < 0 || r >= routers.length) {
      unroutedReports++;
      return;
    }
    final SmartOrderRouter router = routers[r];
    final long child = clOrdId & ~AppIds.CANCEL_FLAG;
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
        execution.lastQuantity(),
        execution.leavesQuantity(),
        execution.cumQuantity(),
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
    final long qty = tradeBust.quantity();
    desk.risk().onFill(tradeBust.instrumentId(), tradeBust.side() == Side.Buy ? -qty : qty, tradeBust.price().mantissa());
  }

  private void onGatewayStatus(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    final int gateway = ControlMessages.gatewayStatusGateway(buffer, offset);
    final boolean up = ControlMessages.gatewayStatus(buffer, offset) == ControlMessages.STATUS_CONNECTED;
    switch (gateway) {
      case AppIds.D2C_GATEWAY -> desk.health().onSession(AppIds.D2C_VENUE, up);
      case AppIds.CLOB_GATEWAY -> {
        desk.health().onSession(AppIds.CLOB_VENUE, up);
        clobSessionUp = up;
        applyClobHealth();
      }
      case AppIds.CLOB_FEED_STATUS -> {
        // The input health learns of a gap only from a jump in sequence numbers. The feed
        // binding announces one before the loop can see it (a frame the ring refused leaves no
        // jump until the next frame), so the jump is shown to it here; on recovery it is seated
        // on the last sequence this loop received, the snapshot's, and the gap cleared.
        if (up) {
          if (clobSeq != 0) {
            desk.health().onBookSequence(AppIds.CLOB_VENUE, clobSeq);
          }
          desk.health().onFeedRecovered(AppIds.CLOB_VENUE);
          clobBookNanos = loop.clock().epochNanos();
        } else {
          if (clobSeq != 0) {
            desk.health().onBookSequence(AppIds.CLOB_VENUE, clobSeq + 2);
          }
          if (clobFeedWhole) {
            feedGaps++;
          }
        }
        clobFeedWhole = up;
        applyClobHealth();
      }
      default -> {
        // a gateway this desk does not trade on
      }
    }
    desk.quoting().poll(loop.clock().epochNanos());
  }

  private void onHousekeeping(final long timerId, final long nowNanos) {
    final long epoch = loop.clock().epochNanos();
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

  public AnalyticsPricing pricing() {
    return pricing;
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

  /** Times the CLOB feed was marked gapped. */
  public long feedGaps() {
    return feedGaps;
  }

  /** True while the CLOB session is up and its book whole: the routers may use it. */
  public boolean clobUsable() {
    return clobSessionUp && clobFeedWhole;
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

  /** The loop this desk runs on. */
  public EventLoop loop() {
    return loop;
  }
}
