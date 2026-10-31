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
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeBustDecoder;
import io.ratesalgo.quoting.Desk;
import io.ratesalgo.router.ChildOrderBook;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.router.RandomSource;
import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.SmartOrderRouter;
import io.ratesalgo.router.VenueParams;
import io.ratesalgo.messages.sbe.VenueKind;
import org.agrona.DirectBuffer;

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
  private final TimerHandler housekeeping = this::onHousekeeping;

  private boolean started;
  private long lastHedgeNanos;
  private long rfqConfirmations;
  private long venueBusts;
  private long hedgeFills;
  private long unroutedReports;

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
              book.view(id, settings.tradeRatePerSecond()),
              orders,
              routeJournal,
              loop.clock(),
              RandomSource.seeded(settings.seed() + id),
              16,
              64,
              AppIds.ROUTER_BASE + ((long) j << 40));
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
    if (!desk.health().healthy(now)) {
      // a sequence gap on the feed: pull now, not at the next timer (the quote-pull-on-gap path)
      desk.quoting().poll(now);
    }
    final long mid = pricing.fairMid(instrumentId);
    if (mid != FairValueSource.NO_VALUE) {
      desk.risk().onFairValue(instrumentId, mid);
    }
    for (int j = 0; j < routers.length; j++) {
      if (routerInstrument[j] == instrumentId) {
        routers[j].onBookUpdate(venueId);
      }
    }
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
    }
    if (!router.isActive(parentId)) {
      hedger.onParentDone(parentId);
      hedgeSink.done(parentId);
    }
  }

  private void onKillRequest(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    killRequest.wrapAndApplyHeader(buffer, offset, header);
    desk.killSwitch().trigger(killRequest.reason(), killRequest.scopeId(), killRequest.requestedBy());
  }

  private void onTradeBust(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    start();
    tradeBust.wrapAndApplyHeader(buffer, offset, header);
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
      case AppIds.CLOB_GATEWAY -> desk.health().onSession(AppIds.CLOB_VENUE, up);
      case AppIds.CLOB_FEED_STATUS -> {
        if (up) {
          desk.health().onFeedRecovered(AppIds.CLOB_VENUE);
        }
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
