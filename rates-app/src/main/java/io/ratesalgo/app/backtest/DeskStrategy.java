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
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportEncoder;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseEncoder;
import io.ratesalgo.router.FrameSink;
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
 * ExecutionReports come back with the desk's own quote and order ids put in. The back-tester has
 * no resting-order cancel, so every child order is sent as a market order and filled by its hedge
 * cost model; the router's cancels are dropped (DECISIONS.md DR-R2W-11). A timer every
 * housekeeping period runs the loop's timers, until five seconds after the last input.
 */
public final class DeskStrategy implements Strategy {

  /** How long the housekeeping timer runs on after the last input: one firm quote's lifetime. */
  static final long TAIL_NANOS = 5_000_000_000L;

  private final UsdUniverse universe;
  private final DeskSettings settings;
  private final ManualClock clock = new ManualClock(0L);
  private final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(1 << 20);
  private final OneToOneRingBuffer[] outbound = new OneToOneRingBuffer[AppIds.OUTBOUND_RINGS];
  private final InboundPublisher publisher = new InboundPublisher(inbound);
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final QuoteDecoder quote = new QuoteDecoder();
  private final NewOrderDecoder newOrder = new NewOrderDecoder();
  private final QuoteResponseEncoder response = new QuoteResponseEncoder();
  private final ExecutionReportEncoder report = new ExecutionReportEncoder();
  private final HeartbeatEncoder heartbeat = new HeartbeatEncoder();
  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final UnsafeBuffer scratch = new UnsafeBuffer(new byte[256]);
  private final Long2LongHashMap quoteOfRfq = new Long2LongHashMap(1024, 0.6f, 0L);
  private final Long2LongHashMap childOfOrder = new Long2LongHashMap(1024, 0.6f, 0L);
  private final org.agrona.concurrent.MessageHandler onOutbound = this::onOutbound;
  private EventLoop loop;
  private DeskGroup desk;
  private StrategyContext ctx;
  private long quotes;
  private long orders;
  private long lastInputNanos;

  public DeskStrategy(final UsdUniverse universe, final DeskSettings settings) {
    this.universe = universe;
    this.settings = settings;
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
    clock.setEpochNanos(Math.max(clock.epochNanos(), ctx.nowEpochNanos()));
    if (buffer != null && !publisher.offer(buffer, offset, length)) {
      throw new IllegalStateException("desk inbound ring full");
    }
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
      final long id =
          ctx.sendOrder(newOrder.instrumentId(), newOrder.side(), OrdType.Market, newOrder.quantity(),
              newOrder.price().mantissa(), true);
      childOfOrder.put(id, newOrder.clOrdId());
      orders++;
    }
    // QuoteWithdraw, OrderCancel and control messages have no back-test counterpart
  }

  @Override
  public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext context) {
    forward(rfq.buffer(), rfq.offset(), rfq.encodedLength());
  }

  @Override
  public void onBookUpdate(final BookUpdateDecoder update, final StrategyContext context) {
    forward(update.buffer(), update.offset(), update.encodedLength());
  }

  @Override
  public void onCurveInput(final CurveInputDecoder input, final StrategyContext context) {
    forward(input.buffer(), input.offset(), input.encodedLength());
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
        .instrumentId(r.instrumentId())
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
}
