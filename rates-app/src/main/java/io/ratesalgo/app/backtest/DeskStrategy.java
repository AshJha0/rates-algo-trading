package io.ratesalgo.app.backtest;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskLoopFactory;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
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
import io.ratesalgo.router.FrameSink;
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
 *       universe (three swap tenors and the future, design section 14 D3) are not shown to it;
 *       their RFQs count as declined.
 * </ul>
 *
 * <p>A callback the back-tester makes while the loop is already running (a cancel answered at
 * once) is queued and delivered after the current duty cycle, so the loop is never re-entered.
 */
public final class DeskStrategy implements Strategy {

  /** How long the housekeeping timer runs on after the last input: one firm quote's lifetime. */
  static final long TAIL_NANOS = 5_000_000_000L;

  private final UsdUniverse universe;
  private final DeskSettings settings;
  private final SimBinding binding;
  private final ManualClock clock = new ManualClock(0L);
  private final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(1 << 20);
  private final OneToOneRingBuffer[] outbound = new OneToOneRingBuffer[AppIds.OUTBOUND_RINGS];
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

  /** The desk on its own recorded journal. */
  public DeskStrategy(final UsdUniverse universe, final DeskSettings settings) {
    this(universe, settings, null);
  }

  /**
   * The desk on a simulator v2 session.
   *
   * @param binding the id translation between the model's universe and the desk's; null for a
   *     recorded journal
   */
  public DeskStrategy(final UsdUniverse universe, final DeskSettings settings, final SimBinding binding) {
    this.universe = universe;
    this.settings = settings;
    this.binding = binding;
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
    final DeskLoopFactory factory = new DeskLoopFactory(universe, settings, FrameSink.NONE);
    loop =
        factory.create(
            EventLoop.builder(new EventClock(clock))
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
        id = ctx.sendOrder(binding.simId(newOrder.instrumentId()), newOrder.side(), newOrder.ordType(),
            newOrder.timeInForce(), newOrder.quantity(), newOrder.price().mantissa(), true);
        orderOfChild.put(newOrder.clOrdId(), id);
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
    cancelReject
        .wrapAndApplyHeader(scratch, 0, headerEncoder)
        .venueId(AppIds.CLOB_VENUE)
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
    final int desk = binding.deskId(rfq.instrumentId());
    if (desk == 0) {
      return;
    }
    final int body = copy(rfq.buffer(), rfq.offset(), rfq.encodedLength());
    rfqPatch.wrap(scratch, body).venueId(AppIds.D2C_VENUE).instrumentId(desk).clientId(binding.clientIdOfTier(rfq.clientId()));
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
    final int body = copy(update.buffer(), update.offset(), update.encodedLength());
    bookPatch.wrap(scratch, body).venueId(AppIds.CLOB_VENUE).instrumentId(desk);
    forward(scratch, body, update.encodedLength());
  }

  @Override
  public void onCurveInput(final CurveInputDecoder input, final StrategyContext context) {
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
    heartbeat.wrapAndApplyHeader(scratch, 0, headerEncoder).sourceId(AppIds.CURVE_HEARTBEAT_SOURCE)
        .timestampNanos(context.nowEpochNanos());
    cycle(scratch, 0, MessageHeaderEncoder.ENCODED_LENGTH + heartbeat.encodedLength());
  }

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
    report
        .wrapAndApplyHeader(scratch, 0, headerEncoder)
        .venueId(AppIds.CLOB_VENUE)
        .clOrdId(child)
        .venueOrderId(r.venueOrderId())
        .execId(r.execId())
        .instrumentId(binding == null ? r.instrumentId() : binding.deskId(r.instrumentId()))
        .side(r.side())
        .ordStatus(r.ordStatus())
        .lastQuantity(r.lastQuantity())
        .leavesQuantity(r.leavesQuantity())
        .cumQuantity(r.cumQuantity())
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
}
