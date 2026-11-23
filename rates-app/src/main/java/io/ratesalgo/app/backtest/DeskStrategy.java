package io.ratesalgo.app.backtest;

import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskLoopFactory;
import io.ratesalgo.app.loop.DeskProfile;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.BusinessDate;
import io.ratesalgo.app.pricing.PricingBindings;
import io.ratesalgo.app.pricing.PricingV2;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.app.pricing.V2InputIds;
import io.ratesalgo.app.sim.V2DeskProcess;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportEncoder;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.OrderCancelDecoder;
import io.ratesalgo.messages.sbe.OrderCancelRejectEncoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseEncoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SimInstrument;
import java.util.ArrayDeque;
import java.util.Deque;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;

/**
 * The trading desk as a P5 back-test {@link Strategy} (API_SPEC.md Section 18.7): the same {@link
 * DeskGroup} the live process runs (quoting engine, risk engine, hedger, routers, pricing
 * binding), on its own event loop driven synchronously by the back-tester's callbacks.
 *
 * <p>Each callback sets the loop's clock to the back-tester's time, copies the message into the
 * loop's inbound ring and runs one duty cycle; what the loop sends is read from its outbound rings
 * and handed to the {@link StrategyContext}: a Quote becomes {@code ctx.quote} (the side the client
 * did not ask for is set equal to the other, since the back-tester settles only the client's
 * side), a NewOrder becomes {@code ctx.sendOrder}. The back-tester's QuoteResponses and
 * ExecutionReports come back with the desk's own quote and order ids put in. A timer every
 * housekeeping period runs the loop's timers, until five seconds after the last input.
 *
 * <p>Two modes.
 *
 * <ul>
 *   <li>Recorded journals (no {@link SimBinding}): the journal is the desk's own inbound journal,
 *       so ids already agree. The back-tester of that version has no resting-order model the desk
 *       could use, so every child order is sent as a market order and filled by its hedge cost
 *       model; cancels and quote withdrawals are dropped (DECISIONS.md DR-R2W-11). This mode is
 *       unchanged, so every recorded figure reproduces.
 *   <li>Simulator v2 (with a {@link SimBinding}): the journal is the shared market model's day.
 *       Instrument ids are translated between the model's universe and the desk's reference data,
 *       venue and curve ids are the desk's, and each RFQ's client id is the fixed id of its tier
 *       (the tier table's ids, {@link SimBinding#clientIdOfTier}). Child orders keep their order
 *       type and time in force, so limit and passive hedges rest on the model's book and fill by
 *       its queue; the router's cancels (OrderCancel) cancel them, and a cancel that comes too late
 *       is answered with an OrderCancelReject. The quoting engine's QuoteWithdraw is forwarded and
 *       honoured when it reaches the venue before the client decides. Market orders pay the book
 *       and its square-root impact (DECISIONS.md DR-R3-04). Instruments outside the desk's
 *       universe (with the recorded universe: three swap tenors and the future, design section 14
 *       D3) are not shown to it; their RFQs are declined and counted as structural declines.
 * </ul>
 *
 * <p>A {@link DeskProfile} on the simulator v2 market ({@link DeskProfile.Market#SIM_V2}, the v2
 * desk and its ablations) runs the loop the v2 process runs, with its six outbound rings, and is
 * shown the market as the simulator v2 venues show it (docs/research/DESK_V2_WIRING.md):
 *
 * <ul>
 *   <li>Each Treasury book is shown on both Treasury CLOBs, 102 and 103, as the shares of the
 *       model's displayed size the simulator's structure file gives them (0.6 and 0.4), in whole
 *       lots rounded down, as {@code ClobSimulator} shows them. Orders to either venue trade on the
 *       one model book, so the consolidated depth is the model's and never more.
 *   <li>TY is shown on the futures venue 104 in the venue's units (contracts, 100 each), and the
 *       desk's orders and fills are converted between those and the back-tester's face units.
 *   <li>Execution reports and cancel rejects carry the venue the child order was sent to.
 *   <li>With pricing v2, each publication of the model's swap rates becomes one curve round of the
 *       pricing v2 build set, as {@code CurveFeedV2} sends it live: the par swaps at the risk
 *       tenors, the SOFR futures and the overnight rate, the front end following {@code
 *       SimV2CurveRound}'s declared rules from the published two-year rate, then one heartbeat.
 *       The round is built from the published (tick-rounded) rates of the journal, the same
 *       information the recorded desk's curve reads; a SOFR futures price can therefore differ from
 *       the live feed's by one 0.0025 step where the unrounded rate sits on a rounding boundary.
 *   <li>The back-tester publishes no trade prints, so the ladder router sees book changes only.
 * </ul>
 *
 * <p>A callback the back-tester makes while the loop is already running (a cancel answered at
 * once) is queued and delivered after the current duty cycle, so the loop is never re-entered.
 */
public final class DeskStrategy implements Strategy {

  /** How long the housekeeping timer runs on after the last input: one firm quote's lifetime. */
  static final long TAIL_NANOS = 5_000_000_000L;

  private final UsdUniverse universe;
  private final DeskProfile profile;
  private final DeskSettings settings;
  private final SimBinding binding;
  /** The simulator v2 market: three order-book venues and the v2 loop's rings. */
  private final boolean v2Market;
  /** Pricing v2: the journal's swap rates become pricing v2 curve rounds. */
  private final boolean curveV2;
  private final ManualClock clock = new ManualClock(0L);
  private final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(1 << 20);
  private final OneToOneRingBuffer[] outbound;
  private final InboundPublisher publisher = new InboundPublisher(inbound);
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final QuoteDecoder quote = new QuoteDecoder();
  private final NewOrderDecoder newOrder = new NewOrderDecoder();
  private final QuoteWithdrawDecoder quoteWithdraw = new QuoteWithdrawDecoder();
  private final OrderCancelDecoder orderCancel = new OrderCancelDecoder();
  private final QuoteResponseEncoder response = new QuoteResponseEncoder();
  private final ExecutionReportEncoder report = new ExecutionReportEncoder();
  private final OrderCancelRejectEncoder cancelReject = new OrderCancelRejectEncoder();
  private final HeartbeatEncoder heartbeat = new HeartbeatEncoder();
  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final QuoteRequestEncoder rfqPatch = new QuoteRequestEncoder();
  private final BookUpdateEncoder bookPatch = new BookUpdateEncoder();
  private final CurveInputEncoder curvePatch = new CurveInputEncoder();
  private final CurveInputEncoder curveRound = new CurveInputEncoder();
  private final UnsafeBuffer scratch = new UnsafeBuffer(new byte[512]);
  private final Long2LongHashMap quoteOfRfq = new Long2LongHashMap(1024, 0.6f, 0L);
  private final Long2LongHashMap childOfOrder = new Long2LongHashMap(1024, 0.6f, 0L);
  private final Long2LongHashMap orderOfChild = new Long2LongHashMap(1024, 0.6f, 0L);
  private final Deque<byte[]> deferred = new ArrayDeque<>();
  private final org.agrona.concurrent.MessageHandler onOutbound = this::onOutbound;
  private EventLoop loop;
  private DeskGroup desk;
  private StrategyContext ctx;
  private boolean inCycle;
  private long quotes;
  private long orders;
  private long withdrawals;
  private long cancels;
  private long lastInputNanos;
  private long lastHeartbeatNanos = Long.MIN_VALUE;
  private long bookSeq;
  private static final long NO_TOP = Long.MIN_VALUE;
  private final Long2LongHashMap topOf = new Long2LongHashMap(64, 0.6f, NO_TOP);

  // simulator v2 market: the venue each child order went to, and each order-book venue's sequence
  private final Long2LongHashMap venueOfOrder = new Long2LongHashMap(1024, 0.6f, 0L);
  private long dustFills;
  private final long[] venueSeq = new long[3];
  private final TierTable tiers;
  // RFQs by the model's tier (row) and the desk tier id they reached (column)
  private final long[][] tierRoutes = new long[3][8];
  private long structuralDeclines;
  private long rfqsSeen;

  // pricing v2: the swap rates of the publication being collected, by model swap in tenor order
  private final int[] swapSimIds;
  private final int[] swapMonths;
  private final long[] swapRate;
  private final boolean[] swapSeen;
  private int swapsSeen;
  private long roundNanos = Long.MIN_VALUE;
  private long curveRounds;
  private int[] roundIds = new int[0];
  private int roundDate = Integer.MIN_VALUE;
  private final PricingV2.Options pricingOptions = PricingV2.Options.defaults();

  /** The desk on its own recorded journal. */
  public DeskStrategy(final UsdUniverse universe, final DeskSettings settings) {
    this(universe, settings, null);
  }

  /**
   * The recorded profile with these settings on a simulator v2 session.
   *
   * @param binding the id translation between the model's universe and the desk's; null for a
   *     recorded journal
   */
  public DeskStrategy(final UsdUniverse universe, final DeskSettings settings, final SimBinding binding) {
    this(DeskProfile.defaults(settings), universe, binding);
  }

  /**
   * Any profile on a simulator v2 session.
   *
   * @param universe the profile's universe ({@link DeskProfile#newUniverse})
   * @param binding the id translation, made for {@code universe}; null only for a recorded journal
   *     and the recorded profile
   */
  public DeskStrategy(final DeskProfile profile, final UsdUniverse universe, final SimBinding binding) {
    this.universe = universe;
    this.profile = profile;
    this.settings = profile.settings();
    this.binding = binding;
    this.v2Market = profile.market() == DeskProfile.Market.SIM_V2;
    this.curveV2 = profile.pricing() == PricingBindings.Version.V2;
    if ((v2Market || curveV2) && binding == null) {
      throw new IllegalArgumentException("a profile with the simulator v2 market or pricing v2 runs on simulator v2 only");
    }
    this.outbound = new OneToOneRingBuffer[v2Market ? AppIds.V2_OUTBOUND_RINGS : AppIds.OUTBOUND_RINGS];
    this.tiers = settings.tiers();
    int swaps = 0;
    final MarketSpec spec = binding == null ? null : binding.spec();
    if (spec != null) {
      for (int i = 0; i < spec.instruments(); i++) {
        swaps += spec.instrument(i).kind() == SimInstrument.Kind.SWAP ? 1 : 0;
      }
      if (v2Market && spec.params().treasuryVenues() != 2) {
        throw new IllegalArgumentException("the desk's v2 market has two Treasury CLOBs; the model has "
            + spec.params().treasuryVenues());
      }
    }
    swapSimIds = new int[swaps];
    swapMonths = new int[swaps];
    swapRate = new long[swaps];
    swapSeen = new boolean[swaps];
    int k = 0;
    for (int i = 0; spec != null && i < spec.instruments(); i++) {
      final SimInstrument s = spec.instrument(i);
      if (s.kind() == SimInstrument.Kind.SWAP) {
        swapSimIds[k] = s.id();
        swapMonths[k] = (int) Math.round(12.0 * s.nodeYears());
        if (k > 0 && swapMonths[k] <= swapMonths[k - 1]) {
          throw new IllegalArgumentException("model swaps must be listed by rising tenor");
        }
        k++;
      }
    }
  }

  @Override
  public String name() {
    return "desk";
  }

  @Override
  public void onStart(final StrategyContext context) {
    this.ctx = context;
    clock.setEpochNanos(context.nowEpochNanos());
    for (int r = 0; r < outbound.length; r++) {
      outbound[r] = RingBufferContract.newOutbound(1 << 20);
    }
    final DeskLoopFactory factory = new DeskLoopFactory(profile, universe, FrameSink.NONE);
    // the recorded profile keeps its three rings, registered exactly as before
    loop =
        factory.create(
            v2Market
                ? V2DeskProcess.loopBuilder(new EventClock(clock), inbound, outbound)
                : EventLoop.builder(new EventClock(clock))
                    .inbound(inbound)
                    .outbound(outbound[AppIds.D2C_GATEWAY])
                    .outbound(outbound[AppIds.CLOB_GATEWAY])
                    .outbound(outbound[AppIds.CONTROL_GATEWAY]));
    desk = factory.last();
    context.scheduleTimer(context.nowEpochNanos() + settings.housekeepingNanos());
  }

  /** Runs the loop on one message at the back-tester's time and forwards what it sends. */
  private void cycle(final DirectBuffer buffer, final int offset, final int length) {
    if (inCycle) {
      // a back-tester callback made from inside this cycle: deliver it after the cycle
      if (buffer != null) {
        final byte[] copy = new byte[length];
        buffer.getBytes(offset, copy);
        deferred.addLast(copy);
      }
      return;
    }
    inCycle = true;
    try {
      clock.setEpochNanos(Math.max(clock.epochNanos(), ctx.nowEpochNanos()));
      offer(buffer, offset, length);
      runAndDrain();
      while (!deferred.isEmpty()) {
        final byte[] next = deferred.pollFirst();
        offer(new UnsafeBuffer(next), 0, next.length);
        runAndDrain();
      }
    } finally {
      inCycle = false;
    }
  }

  private void offer(final DirectBuffer buffer, final int offset, final int length) {
    if (buffer != null && !publisher.offer(buffer, offset, length)) {
      throw new IllegalStateException("desk inbound ring full");
    }
  }

  private void runAndDrain() {
    loop.doWork();
    for (final OneToOneRingBuffer ring : outbound) {
      ring.read(onOutbound, Integer.MAX_VALUE);
    }
  }

  private void forward(final DirectBuffer buffer, final int bodyOffset, final int encodedLength) {
    lastInputNanos = ctx.nowEpochNanos();
    final int offset = bodyOffset - MessageHeaderDecoder.ENCODED_LENGTH;
    cycle(buffer, offset, MessageHeaderDecoder.ENCODED_LENGTH + encodedLength);
  }

  /** Copies a frame (header and body) into the scratch buffer and returns the body offset there. */
  private int copy(final DirectBuffer buffer, final int bodyOffset, final int encodedLength) {
    scratch.putBytes(0, buffer, bodyOffset - MessageHeaderDecoder.ENCODED_LENGTH,
        MessageHeaderDecoder.ENCODED_LENGTH + encodedLength);
    return MessageHeaderDecoder.ENCODED_LENGTH;
  }

  private void onOutbound(final int msgTypeId, final MutableDirectBuffer buffer, final int index, final int length) {
    header.wrap(buffer, index);
    final int body = index + MessageHeaderDecoder.ENCODED_LENGTH;
    if (msgTypeId == QuoteDecoder.TEMPLATE_ID) {
      quote.wrap(buffer, body, header.blockLength(), header.version());
      final long px = quote.bidQuantity() > 0 ? quote.bidPrice().mantissa() : quote.askPrice().mantissa();
      quoteOfRfq.put(quote.rfqId(), quote.quoteId());
      quotes++;
      ctx.quote(quote.rfqId(), px, px);
    } else if (msgTypeId == NewOrderDecoder.TEMPLATE_ID) {
      newOrder.wrap(buffer, body, header.blockLength(), header.version());
      final long id;
      if (binding == null) {
        id = ctx.sendOrder(newOrder.instrumentId(), newOrder.side(), OrdType.Market, newOrder.quantity(),
            newOrder.price().mantissa(), true);
      } else {
        // a futures venue counts contracts; the back-tester counts face, as the desk does
        final long quantity = Math.multiplyExact(newOrder.quantity(), venueScale(newOrder.instrumentId()));
        id = ctx.sendOrder(binding.simId(newOrder.instrumentId()), newOrder.side(), newOrder.ordType(),
            newOrder.timeInForce(), quantity, newOrder.price().mantissa(), true);
        orderOfChild.put(newOrder.clOrdId(), id);
        venueOfOrder.put(id, newOrder.venueId());
      }
      childOfOrder.put(id, newOrder.clOrdId());
      orders++;
    } else if (binding != null && msgTypeId == QuoteWithdrawDecoder.TEMPLATE_ID) {
      quoteWithdraw.wrap(buffer, body, header.blockLength(), header.version());
      withdrawals += ctx.withdrawQuote(quoteWithdraw.rfqId()) ? 1 : 0;
    } else if (binding != null && msgTypeId == OrderCancelDecoder.TEMPLATE_ID) {
      orderCancel.wrap(buffer, body, header.blockLength(), header.version());
      cancel(orderCancel.clOrdId(), orderCancel.origClOrdId(), orderCancel.instrumentId());
    }
    // recorded journals: QuoteWithdraw and OrderCancel have no counterpart; control messages never do
  }

  /** Simulator v2: cancels a resting child, or tells the router the cancel came too late. */
  private void cancel(final long request, final long child, final int deskInstrument) {
    final long order = orderOfChild.get(child);
    if (order != 0L && ctx.cancelOrder(order)) {
      cancels++;
      return;
    }
    final long venue = venueOfOrder.get(order);
    cancelReject
        .wrapAndApplyHeader(scratch, 0, headerEncoder)
        .venueId(venue == 0L ? AppIds.CLOB_VENUE : (int) venue)
        .clOrdId(request)
        .origClOrdId(child)
        .instrumentId(deskInstrument)
        .ordStatus(OrdStatus.Filled)
        .responseTo((short) 1)
        .timestampNanos(ctx.nowEpochNanos());
    cycle(scratch, 0, MessageHeaderEncoder.ENCODED_LENGTH + cancelReject.encodedLength());
  }

  @Override
  public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext context) {
    if (binding == null) {
      forward(rfq.buffer(), rfq.offset(), rfq.encodedLength());
      return;
    }
    rfqsSeen++;
    final int desk = binding.deskId(rfq.instrumentId());
    if (desk == 0) {
      // an instrument outside the desk's universe: declined whatever the strategy
      structuralDeclines++;
      return;
    }
    final int client = binding.clientIdOfTier(rfq.clientId());
    tierRoutes[rfq.clientId() - 1][tiers.tierOf(client).tier()]++;
    final int body = copy(rfq.buffer(), rfq.offset(), rfq.encodedLength());
    rfqPatch.wrap(scratch, body).venueId(AppIds.D2C_VENUE).instrumentId(desk).clientId(client);
    forward(scratch, body, rfq.encodedLength());
  }

  @Override
  public void onBookUpdate(final BookUpdateDecoder update, final StrategyContext context) {
    if (binding == null) {
      forward(update.buffer(), update.offset(), update.encodedLength());
      return;
    }
    final int desk = binding.deskId(update.instrumentId());
    if (desk == 0) {
      return;
    }
    if (v2Market) {
      showOnV2Venues(update, desk);
      return;
    }
    // the journal carries each book's top only, as a change of level 0; the desk keeps its book by
    // price, so the old top price is deleted before the new one is set, or stale levels would stay
    final long key = ((long) desk << 1) | (update.side() == Side.Buy ? 0L : 1L);
    final long previous = topOf.get(key);
    final long price = update.price().mantissa();
    if (previous != NO_TOP && previous != price) {
      final int body = copy(update.buffer(), update.offset(), update.encodedLength());
      bookPatch.wrap(scratch, body).venueId(AppIds.CLOB_VENUE).instrumentId(desk).venueSeqNo(++bookSeq)
          .action(BookAction.Delete).quantity(0L);
      bookPatch.price().mantissa(previous);
      forward(scratch, body, update.encodedLength());
    }
    topOf.put(key, price);
    final int body = copy(update.buffer(), update.offset(), update.encodedLength());
    // the desk's feed check expects a gapless sequence; updates of instruments it does not hold are
    // not shown to it, so the sequence is renumbered over the updates it is shown
    bookPatch.wrap(scratch, body).venueId(AppIds.CLOB_VENUE).instrumentId(desk).venueSeqNo(++bookSeq);
    forward(scratch, body, update.encodedLength());
  }

  /**
   * Simulator v2 market: one model book's top as the venues show it. A Treasury is shown on both
   * CLOBs at their shares of the displayed size in whole lots, rounded down as {@code
   * ClobSimulator} rounds; TY on the futures venue in contracts.
   */
  private void showOnV2Venues(final BookUpdateDecoder update, final int desk) {
    final SimInstrument model = binding.simInstrument(update.instrumentId());
    final long price = update.price().mantissa();
    if (model.kind() == SimInstrument.Kind.FUTURE) {
      final long scale = venueScale(desk);
      showTop(AppIds.FUTURES_VENUE, desk, update.side(), price, update.quantity() / scale, update.timestampNanos());
      return;
    }
    final long lot = Math.round(model.minQuantity() * model.unitFaceUsd() * 100.0);
    final long lots = update.quantity() / lot;
    final MarketSpec spec = binding.spec();
    for (int v = 0; v < 2; v++) {
      final long shown = (long) Math.floor(spec.params().treasuryVenueShare(v) * lots) * lot;
      showTop(v == 0 ? AppIds.CLOB_VENUE : AppIds.CLOB2_VENUE, desk, update.side(), price, shown,
          update.timestampNanos());
    }
  }

  /** One venue's top of one side: the old top deleted when it moved or emptied, the new one set. */
  private void showTop(
      final int venue, final int desk, final Side side, final long price, final long quantity, final long stamp) {
    final long key = ((long) (venue - AppIds.CLOB_VENUE) << 40) | ((long) desk << 1) | (side == Side.Buy ? 0L : 1L);
    final long previous = topOf.get(key);
    if (previous != NO_TOP && (previous != price || quantity <= 0L)) {
      sendLevel(venue, desk, side, BookAction.Delete, previous, 0L, stamp);
      topOf.remove(key);
    }
    if (quantity <= 0L) {
      return;
    }
    topOf.put(key, price);
    sendLevel(venue, desk, side, BookAction.Change, price, quantity, stamp);
  }

  private void sendLevel(
      final int venue, final int desk, final Side side, final BookAction action, final long price, final long quantity,
      final long stamp) {
    bookPatch
        .wrapAndApplyHeader(scratch, 0, headerEncoder)
        .venueId(venue)
        .instrumentId(desk)
        .venueSeqNo(++venueSeq[venue - AppIds.CLOB_VENUE])
        .side(side)
        .action(action)
        .level((short) 0)
        .quantity(quantity)
        .orderCount(quantity > 0L ? 1 : 0)
        .timestampNanos(stamp);
    bookPatch.price().mantissa(price);
    forward(scratch, MessageHeaderEncoder.ENCODED_LENGTH, bookPatch.encodedLength());
  }

  /** Back-tester (face) quantity per venue quantity of a desk instrument: a contract's face on a futures venue, else 1. */
  private long venueScale(final int deskInstrument) {
    final UsdUniverse.FutureSpec f = universe.future(deskInstrument);
    return f == null ? 1L : f.deskPerVenue();
  }

  @Override
  public void onCurveInput(final CurveInputDecoder input, final StrategyContext context) {
    if (curveV2) {
      collectRound(input);
      return;
    }
    if (binding == null) {
      forward(input.buffer(), input.offset(), input.encodedLength());
    } else {
      final int desk = binding.deskId(input.instrumentId());
      if (desk != 0) {
        final int body = copy(input.buffer(), input.offset(), input.encodedLength());
        curvePatch.wrap(scratch, body).curveId(AppIds.SOFR_CURVE).instrumentId(desk);
        forward(scratch, body, input.encodedLength());
      }
    }
    // the back-tester does not pass Heartbeats to strategies; the curve feed's heartbeat travels
    // with its curve inputs live, so it is restored here or input health would time out
    // (simulator v2: once per publication time, as the live feed sends one per round)
    if (binding != null && context.nowEpochNanos() == lastHeartbeatNanos) {
      return;
    }
    lastHeartbeatNanos = context.nowEpochNanos();
    heartbeat.wrapAndApplyHeader(scratch, 0, headerEncoder).sourceId(AppIds.CURVE_HEARTBEAT_SOURCE)
        .timestampNanos(context.nowEpochNanos());
    cycle(scratch, 0, MessageHeaderEncoder.ENCODED_LENGTH + heartbeat.encodedLength());
  }

  /**
   * Pricing v2: collects one publication of the model's swap rates and, once every model swap of
   * that time is in, sends the round. The journal's fixing is not used: under pricing v2 the
   * overnight rate comes with the round, as the live feed sends it.
   */
  private void collectRound(final CurveInputDecoder input) {
    if (input.kind() != CurveInputKind.ParSwap) {
      return;
    }
    final int k = swapSlot(input.instrumentId());
    if (k < 0) {
      return;
    }
    if (input.timestampNanos() != roundNanos) {
      roundNanos = input.timestampNanos();
      java.util.Arrays.fill(swapSeen, false);
      swapsSeen = 0;
    }
    if (!swapSeen[k]) {
      swapSeen[k] = true;
      swapsSeen++;
    }
    swapRate[k] = input.value().mantissa();
    if (swapsSeen == swapSimIds.length) {
      sendRound(input.timestampNanos());
      swapsSeen = 0;
      java.util.Arrays.fill(swapSeen, false);
    }
  }

  private int swapSlot(final int simId) {
    for (int k = 0; k < swapSimIds.length; k++) {
      if (swapSimIds[k] == simId) {
        return k;
      }
    }
    return -1;
  }

  /** The build set of pricing v2 on the clock's business date, then any quoted swap not in it, as {@code CurveFeedV2}. */
  private void layoutRound() {
    final int today = BusinessDate.tradeDate(clock.epochNanos(), pricingOptions.cutHour(), Calendars.US_SOFR);
    if (today == roundDate) {
      return;
    }
    roundDate = today;
    final PricingV2 probe = new PricingV2(universe, clock, pricingOptions);
    final java.util.List<Integer> ids = new java.util.ArrayList<>();
    for (int i = 0; i < probe.inputCount(); i++) {
      ids.add(probe.inputId(i));
    }
    for (final UsdUniverse.SwapSpec s : universe.swaps()) {
      if (!ids.contains(s.instrumentId()) && !ids.contains(V2InputIds.parSwap(s.tenorMonths()))) {
        ids.add(s.instrumentId());
      }
    }
    roundIds = ids.stream().mapToInt(Integer::intValue).toArray();
  }

  private void sendRound(final long stamp) {
    layoutRound();
    for (final int id : roundIds) {
      final CurveInputKind kind;
      final long value;
      final String tenor;
      if (id == V2InputIds.OVERNIGHT) {
        // SimV2CurveRound: the overnight rate is the two-year swap's opening rate, all day
        kind = CurveInputKind.Fixing;
        value = Math.round(binding.simInstrument(swapSimIds[0]).initialMid() * 1e9);
        tenor = "ON  ";
      } else if (V2InputIds.isSr1(id) || V2InputIds.isSr3(id)) {
        kind = CurveInputKind.Future;
        value = roundTo(100_000_000_000L - swapRate[0], FUTURES_TICK_MANTISSA);
        tenor = V2InputIds.isSr1(id) ? "SR1 " : "SR3 ";
      } else {
        final UsdUniverse.SwapSpec s = universe.swap(id);
        final int months = s != null ? s.tenorMonths() : V2InputIds.parSwapMonths(id);
        kind = CurveInputKind.ParSwap;
        value = parSwap(months);
        tenor = (months % 12 == 0 ? (months / 12) + "Y   " : months + "M   ").substring(0, 4);
      }
      curveRound
          .wrapAndApplyHeader(scratch, 0, headerEncoder)
          .curveId(AppIds.SOFR_CURVE)
          .kind(kind)
          .instrumentId(id)
          .tenor(tenor)
          .timestampNanos(stamp);
      curveRound.value().mantissa(value);
      forward(scratch, MessageHeaderEncoder.ENCODED_LENGTH, curveRound.encodedLength());
    }
    heartbeat.wrapAndApplyHeader(scratch, 0, headerEncoder).sourceId(AppIds.CURVE_HEARTBEAT_SOURCE).timestampNanos(stamp);
    cycle(scratch, 0, MessageHeaderEncoder.ENCODED_LENGTH + heartbeat.encodedLength());
    curveRounds++;
  }

  /**
   * The published par rate of a tenor, SimV2CurveRound's rule on the published model rates: the
   * two-year rate at and below two years, the 30-year above, linear in months between model
   * tenors, rounded to the swap tick.
   */
  private long parSwap(final int months) {
    final int last = swapMonths.length - 1;
    if (months <= swapMonths[0]) {
      return swapRate[0];
    }
    if (months >= swapMonths[last]) {
      return swapRate[last];
    }
    int hi = 1;
    while (swapMonths[hi] < months) {
      hi++;
    }
    if (swapMonths[hi] == months) {
      return swapRate[hi];
    }
    final double w = (double) (months - swapMonths[hi - 1]) / (swapMonths[hi] - swapMonths[hi - 1]);
    final double rate = (1.0 - w) * swapRate[hi - 1] + w * swapRate[hi];
    final long tick = Math.round(binding.simInstrument(swapSimIds[0]).tick() * 1e9);
    return roundTo(Math.round(rate), tick);
  }

  /** {@code value} to the nearest multiple of {@code tick}, half away from zero. */
  static long roundTo(final long value, final long tick) {
    final long n = Math.floorDiv(Math.abs(value) + tick / 2, tick);
    return Long.signum(value) * n * tick;
  }

  /** Published SOFR futures price increment, 0.0025, as a Decimal9 mantissa (SimV2CurveRound.FUTURES_TICK). */
  static final long FUTURES_TICK_MANTISSA = 2_500_000L;

  @Override
  public void onQuoteResponse(final QuoteResponseDecoder r, final StrategyContext context) {
    final long quoteId = quoteOfRfq.remove(r.rfqId());
    response
        .wrapAndApplyHeader(scratch, 0, headerEncoder)
        .venueId(AppIds.D2C_VENUE)
        .rfqId(r.rfqId())
        .quoteId(quoteId)
        .status(r.status())
        .tradedQuantity(r.tradedQuantity())
        .timestampNanos(r.timestampNanos());
    response.tradedPrice().mantissa(r.tradedPrice().mantissa());
    response.coverPrice().mantissa(r.coverPrice().mantissa());
    cycle(scratch, 0, MessageHeaderEncoder.ENCODED_LENGTH + response.encodedLength());
  }

  @Override
  public void onExecutionReport(final ExecutionReportDecoder r, final StrategyContext context) {
    final long child = childOfOrder.get(r.clOrdId());
    final int instrument = binding == null ? r.instrumentId() : binding.deskId(r.instrumentId());
    final long scale = binding == null ? 1L : venueScale(instrument);
    // the model's book fills TY in fractions of a contract; the venue reports whole venue units, so
    // the cumulative fill is rounded down and the last fill is its increase (under 0.01 of a
    // contract is never reported to the desk)
    final long cum = r.cumQuantity() / scale;
    final long last = cum - (r.cumQuantity() - r.lastQuantity()) / scale;
    final long leaves = (r.cumQuantity() + r.leavesQuantity()) / scale - cum;
    if (scale > 1L && last == 0L && r.ordStatus() == OrdStatus.PartiallyFilled) {
      dustFills++;
      return;
    }
    final long venue = binding == null ? 0L : venueOfOrder.get(r.clOrdId());
    report
        .wrapAndApplyHeader(scratch, 0, headerEncoder)
        .venueId(venue == 0L ? AppIds.CLOB_VENUE : (int) venue)
        .clOrdId(child)
        .venueOrderId(r.venueOrderId())
        .execId(r.execId())
        .instrumentId(instrument)
        .side(r.side())
        .ordStatus(r.ordStatus())
        .lastQuantity(last)
        .leavesQuantity(leaves)
        .cumQuantity(cum)
        .transactNanos(r.transactNanos());
    report.lastPrice().mantissa(r.lastPrice().mantissa());
    cycle(scratch, 0, MessageHeaderEncoder.ENCODED_LENGTH + report.encodedLength());
  }

  @Override
  public void onTimer(final long timerId, final long nowEpochNanos, final StrategyContext context) {
    cycle(null, 0, 0);
    // after the journal's last input the timers run out one quote lifetime later, so the run ends
    if (nowEpochNanos - lastInputNanos < TAIL_NANOS) {
      context.scheduleTimer(nowEpochNanos + settings.housekeepingNanos());
    }
  }

  /** The desk inside the strategy (null before the first event). */
  public DeskGroup desk() {
    return desk;
  }

  /** Quotes handed to the back-tester. */
  public long quotes() {
    return quotes;
  }

  /** Child orders handed to the back-tester. */
  public long orders() {
    return orders;
  }

  /** Quote withdrawals the back-tester honoured (simulator v2). */
  public long withdrawals() {
    return withdrawals;
  }

  /** Resting child orders cancelled at the router's request (simulator v2). */
  public long cancels() {
    return cancels;
  }

  /** RFQs the back-tester delivered (simulator v2), shown to the desk or not. */
  public long rfqsSeen() {
    return rfqsSeen;
  }

  /**
   * RFQs on an instrument outside the desk's universe (simulator v2): declined for a structural
   * reason, whatever the strategy would have done.
   */
  public long structuralDeclines() {
    return structuralDeclines;
  }

  /** Pricing v2 curve rounds sent to the desk. */
  public long curveRounds() {
    return curveRounds;
  }

  /**
   * RFQs whose model tier has index {@code modelTier} (0 is A) that reached the desk tier with id
   * {@code deskTier} of the profile's tier table (simulator v2).
   */
  public long tierRoutes(final int modelTier, final int deskTier) {
    return tierRoutes[modelTier][deskTier];
  }

  /** Structural counts for the experiment's session record; no P&amp;L. */
  @Override
  public java.util.Map<String, Long> counters() {
    final java.util.Map<String, Long> m = new java.util.LinkedHashMap<>();
    m.put("rfqs_seen", rfqsSeen);
    m.put("structural_declines", structuralDeclines);
    m.put("desk_quotes", quotes);
    m.put("child_orders", orders);
    m.put("withdrawals", withdrawals);
    m.put("cancels", cancels);
    m.put("curve_rounds", curveRounds);
    m.put("futures_dust_fills", dustFills);
    return m;
  }
}
