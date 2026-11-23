package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskLoopFactory;
import io.ratesalgo.app.loop.DeskProfile;
import io.ratesalgo.app.pricing.PricingV2;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.app.pricing.V2InputIds;
import io.ratesalgo.app.sim.V2DeskProcess;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.ExecutionReportEncoder;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrderCancelDecoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.QuoteResponseEncoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradePrintEncoder;
import io.ratesalgo.router.FrameSink;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.junit.jupiter.api.Test;

/**
 * The allocation gate of {@link WiredLoopAllocationTest} for the v2 desk profile: pricing v2 with
 * its conflated reprice, strategy v2's per-RFQ path, the ladder router over three order-book
 * venues, trade prints and the futures unit conversion, all behind the handlers the desk
 * registers.
 *
 * <p>Unlike the version 1 gate this one is stepped on the test thread with a manual clock, so it
 * does not depend on the machine's load: the loop's {@code doWork} runs between the test's own
 * publishes, and the bytes the thread allocates inside each {@code doWork} are read from the JVM
 * and counted against the kind of message that caused them, so a failure says where to look. The
 * bound is the version 1 gate's (DR-R2W-12): no window after the first above 1,024 bytes, at
 * least one at zero.
 *
 * <p>One allocation is known and kept out of the run by its length: each router's set of execution
 * ids ({@code ChildOrderBook.execSeen}, rates-router) is sized for an expected number of fills and
 * doubles on the loop thread when a router has seen about 4,500 in a day, a single 131 kB array.
 * At 3,200 cycles no router gets there; at 5,400 the busiest one did, in the third window, and
 * the count by cause named the execution report. It is the router's bound, reported in
 * docs/research/DESK_V2_WIRING.md, and not something the wiring can remove.
 *
 * <p>The same run times the loop on the two paths a desk head asks about: one RFQ from the
 * QuoteRequest to the Quote, and one complete curve round's reprice. The figures are printed, not
 * asserted; under the build's coverage agent they are an upper bound.
 */
class WiredLoopV2AllocationTest {

  private static final int WARM_UP_CYCLES = 1_600;
  private static final int WINDOW_CYCLES = 400;
  private static final int WINDOWS = 4;
  private static final long LATER_WINDOW_BOUND = 1024L;
  private static final long MILLI = 1_000_000L;

  private final UsdUniverse universe = UsdUniverse.simV2();
  private final ManualClock source = new ManualClock(V2DeskProcess.SESSION_OPEN_EPOCH_NANOS + 5_400_000_000_000L);
  private final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(1 << 20);
  private final OneToOneRingBuffer[] outbound = new OneToOneRingBuffer[AppIds.V2_OUTBOUND_RINGS];
  private final InboundPublisher publisher = new InboundPublisher(inbound);
  private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
  private final UnsafeBuffer reply = new UnsafeBuffer(new byte[256]);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
  private final QuoteRequestEncoder rfq = new QuoteRequestEncoder();
  private final QuoteResponseEncoder response = new QuoteResponseEncoder();
  private final BookUpdateEncoder book = new BookUpdateEncoder();
  private final TradePrintEncoder print = new TradePrintEncoder();
  private final CurveInputEncoder curve = new CurveInputEncoder();
  private final HeartbeatEncoder heartbeat = new HeartbeatEncoder();
  private final ExecutionReportEncoder report = new ExecutionReportEncoder();
  private final QuoteDecoder quote = new QuoteDecoder();
  private final NewOrderDecoder order = new NewOrderDecoder();
  private final OrderCancelDecoder cancel = new OrderCancelDecoder();
  private final long[] pendingRfq = new long[1 << 12];
  private final long[] pendingQuote = new long[1 << 12];
  private final long[] pendingPrice = new long[1 << 12];
  private int pendingHead;
  private int pendingTail;
  private final MessageHandler onQuote = this::onQuote;
  private final MessageHandler[] onOrder = new MessageHandler[AppIds.V2_OUTBOUND_RINGS];
  private final int[] rfqInstruments;
  private final int[] inputIds;
  private final long[] inputOpen;
  private final UsdUniverse.BondSpec[] bonds = universe.bonds().toArray(new UsdUniverse.BondSpec[0]);
  private final UsdUniverse.FutureSpec future = universe.futures().get(0);
  private final long[] feedSeq = new long[3];
  private final long[] rfqNanos = new long[1 << 16];
  private final long[] repriceNanos = new long[1 << 14];
  private int rfqTimes;
  private int repriceTimes;
  private EventLoop loop;
  private DeskGroup desk;
  private long rfqId;
  private long execId;
  private long cycle;
  private long ordersFilled;

  WiredLoopV2AllocationTest() {
    // every instrument a venue lists: the swaps but the 1-year, and the Treasuries
    rfqInstruments = new int[universe.swaps().size() - 1 + universe.bonds().size()];
    int n = 0;
    for (int j = 1; j < universe.swaps().size(); j++) {
      rfqInstruments[n++] = universe.swaps().get(j).instrumentId();
    }
    for (final int id : universe.bondIds()) {
      rfqInstruments[n++] = id;
    }
    final PricingV2 probe = new PricingV2(universe, source, PricingV2.Options.defaults());
    inputIds = new int[probe.inputCount()];
    inputOpen = new long[inputIds.length];
    for (int i = 0; i < inputIds.length; i++) {
      inputIds[i] = probe.inputId(i);
      final UsdUniverse.SwapSpec s = universe.swap(inputIds[i]);
      inputOpen[i] = (s != null ? s : universe.swaps().get(0)).initialMid();
    }
  }

  private void onQuote(final int type, final MutableDirectBuffer b, final int index, final int length) {
    if (type == QuoteDecoder.TEMPLATE_ID) {
      quote.wrapAndApplyHeader(b, index, headerDecoder);
      final int k = pendingTail++ & 0xFFF;
      pendingRfq[k] = quote.rfqId();
      pendingQuote[k] = quote.quoteId();
      pendingPrice[k] = quote.bidQuantity() > 0 ? quote.bidPrice().mantissa() : quote.askPrice().mantissa();
    }
  }

  /** An order-book venue: fills every child in full at its price, acknowledges every cancel. */
  private void onOrder(final int venueId, final int type, final MutableDirectBuffer b, final int index) {
    if (type == NewOrderDecoder.TEMPLATE_ID) {
      order.wrapAndApplyHeader(b, index, headerDecoder);
      ordersFilled++;
      send(venueId, order.clOrdId(), order.instrumentId(), order.side(), OrdStatus.Filled, order.price().mantissa(),
          order.quantity(), order.quantity());
    } else if (type == OrderCancelDecoder.TEMPLATE_ID) {
      cancel.wrapAndApplyHeader(b, index, headerDecoder);
      send(venueId, cancel.clOrdId(), cancel.instrumentId(), cancel.side(), OrdStatus.Cancelled, 0L, 0L, 0L);
    }
  }

  private void send(
      final int venueId, final long clOrdId, final int instrumentId, final Side side, final OrdStatus status,
      final long price, final long last, final long cum) {
    report.wrapAndApplyHeader(reply, 0, header).venueId(venueId).clOrdId(clOrdId).venueOrderId(clOrdId)
        .execId(++execId).instrumentId(instrumentId).side(side).ordStatus(status).lastQuantity(last)
        .leavesQuantity(0L).cumQuantity(cum).transactNanos(source.epochNanos());
    report.lastPrice().mantissa(price);
    assertTrue(publisher.offer(reply, 0, MessageHeaderEncoder.ENCODED_LENGTH + report.encodedLength()));
  }

  private static final int CURVE = 0;
  private static final int HEARTBEAT = 1;
  private static final int BOOK = 2;
  private static final int PRINT = 3;
  private static final int RFQ = 4;
  private static final int RESPONSE = 5;
  private static final int TIMER = 6;
  private static final int REPORT = 7;
  private static final String[] KINDS =
      {"curve input", "heartbeat", "book update", "trade print", "RFQ", "quote response", "timer", "execution report"};

  private final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
  private final long[] bytesByKind = new long[KINDS.length];
  private long loopBytes;

  /** One duty cycle of the loop, with what it allocated counted against the kind of message that caused it. */
  private int work(final int kind) {
    final long before = threads.getCurrentThreadAllocatedBytes();
    final int work = loop.doWork();
    final long allocated = threads.getCurrentThreadAllocatedBytes() - before;
    bytesByKind[kind] += allocated;
    loopBytes += allocated;
    return work;
  }

  /** One message into the loop, a millisecond later, and everything it leads to. */
  private long run(final int kind, final int length) {
    assertTrue(publisher.offer(buffer, 0, length));
    source.advance(MILLI);
    final long start = System.nanoTime();
    work(kind);
    final long took = System.nanoTime() - start;
    settle();
    return took;
  }

  /** The venues answer what the desk sent until the loop has nothing more to do. */
  private void settle() {
    int work;
    do {
      work = outbound[AppIds.D2C_GATEWAY].read(onQuote, 256);
      work += outbound[AppIds.CLOB_GATEWAY].read(onOrder[AppIds.CLOB_GATEWAY], 256);
      work += outbound[AppIds.CLOB2_GATEWAY].read(onOrder[AppIds.CLOB2_GATEWAY], 256);
      work += outbound[AppIds.FUTURES_GATEWAY].read(onOrder[AppIds.FUTURES_GATEWAY], 256);
      work += outbound[AppIds.CONTROL_GATEWAY].read(NOTHING, 256);
      work += work(REPORT);
    } while (work > 0);
  }

  private static final MessageHandler NOTHING = (type, b, index, length) -> { };

  private void level(final int slot, final int venueId, final int instrumentId, final boolean bid, final long price, final long quantity) {
    book.wrapAndApplyHeader(buffer, 0, header).venueId(venueId).instrumentId(instrumentId).venueSeqNo(++feedSeq[slot])
        .side(bid ? Side.Buy : Side.Sell).action(BookAction.Change).level((short) 1).quantity(quantity).orderCount(1)
        .timestampNanos(source.epochNanos());
    book.price().mantissa(price);
    run(BOOK, MessageHeaderEncoder.ENCODED_LENGTH + book.encodedLength());
  }

  /** One cycle of traffic: a curve round, both sides of every book, prints, RFQs and their outcomes. */
  private void cycle() {
    cycle++;
    final long stamp = source.epochNanos();
    final long shiftBp = cycle % 7 - 3;
    long last = 0;
    for (int i = 0; i < inputIds.length; i++) {
      final int id = inputIds[i];
      final CurveInputKind kind =
          id == V2InputIds.OVERNIGHT
              ? CurveInputKind.Fixing
              : V2InputIds.isSr1(id) || V2InputIds.isSr3(id) ? CurveInputKind.Future : CurveInputKind.ParSwap;
      // rates a few hundredths of a basis point around the open, so every round moves and solves
      final long rate = inputOpen[i] + shiftBp * 200_000L;
      curve.wrapAndApplyHeader(buffer, 0, header).curveId(AppIds.SOFR_CURVE).kind(kind).instrumentId(id).tenor("    ")
          .timestampNanos(stamp);
      curve.value().mantissa(
          kind == CurveInputKind.Fixing ? inputOpen[i] : kind == CurveInputKind.Future ? 100_000_000_000L - rate : rate);
      last = run(CURVE, MessageHeaderEncoder.ENCODED_LENGTH + curve.encodedLength());
    }
    // the last input completes the round: its dispatch is the reprice
    repriceNanos[repriceTimes++ & (repriceNanos.length - 1)] = last;
    heartbeat.wrapAndApplyHeader(buffer, 0, header).sourceId(AppIds.CURVE_HEARTBEAT_SOURCE).timestampNanos(stamp);
    run(HEARTBEAT, MessageHeaderEncoder.ENCODED_LENGTH + heartbeat.encodedLength());

    // indexed, not for-each: an iterator a cycle would be this test's own allocation
    for (int j = 0; j < bonds.length; j++) {
      final UsdUniverse.BondSpec b = bonds[j];
      final long quantity = 5_000_000_000L + (cycle & 7) * 100_000_000L;
      level(0, AppIds.CLOB_VENUE, b.instrumentId(), true, b.initialMid() - b.tick(), quantity);
      level(0, AppIds.CLOB_VENUE, b.instrumentId(), false, b.initialMid() + b.tick(), quantity);
      level(1, AppIds.CLOB2_VENUE, b.instrumentId(), true, b.initialMid() - b.tick(), quantity);
      level(1, AppIds.CLOB2_VENUE, b.instrumentId(), false, b.initialMid() + b.tick(), quantity);
    }
    final UsdUniverse.FutureSpec f = future;
    level(2, AppIds.FUTURES_VENUE, f.instrumentId(), true, f.initialMid() - f.tick(), 500 * 100L + (cycle & 3) * 100L);
    level(2, AppIds.FUTURES_VENUE, f.instrumentId(), false, f.initialMid() + f.tick(), 500 * 100L + (cycle & 3) * 100L);

    final UsdUniverse.BondSpec printed = bonds[(int) (cycle % bonds.length)];
    print.wrapAndApplyHeader(buffer, 0, header).venueId((cycle & 1) == 0 ? AppIds.CLOB_VENUE : AppIds.CLOB2_VENUE)
        .instrumentId(printed.instrumentId()).venueSeqNo(cycle).aggressorSide((cycle & 2) == 0 ? Side.Buy : Side.Sell)
        .quantity(200_000_000L).timestampNanos(source.epochNanos());
    print.price().mantissa(printed.initialMid() + printed.tick());
    run(PRINT, MessageHeaderEncoder.ENCODED_LENGTH + print.encodedLength());
    print.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.FUTURES_VENUE).instrumentId(f.instrumentId())
        .venueSeqNo(cycle).aggressorSide((cycle & 2) == 0 ? Side.Sell : Side.Buy).quantity(3 * 100L)
        .timestampNanos(source.epochNanos());
    print.price().mantissa(f.initialMid() - f.tick());
    run(PRINT, MessageHeaderEncoder.ENCODED_LENGTH + print.encodedLength());

    for (int k = 0; k < 8; k++) {
      rfqId++;
      rfq.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.D2C_VENUE).rfqId(rfqId)
          .clientId(2001 + (int) (rfqId % 3)).instrumentId(rfqInstruments[(int) (rfqId % rfqInstruments.length)])
          .side((rfqId & 2) == 0 ? Side.Buy : Side.Sell).quantity(1_000_000_000L).dealerCount((short) 4)
          .receivedNanos(source.epochNanos()).expiryNanos(0L);
      rfq.putCurrency((byte) 'U', (byte) 'S', (byte) 'D');
      rfqNanos[rfqTimes++ & (rfqNanos.length - 1)] = run(RFQ, MessageHeaderEncoder.ENCODED_LENGTH + rfq.encodedLength());
    }
    // the venue's outcome for every quote the loop sent: one in four a fill with the cover shown
    while (pendingHead != pendingTail) {
      final int k = pendingHead++ & 0xFFF;
      final boolean done = (pendingQuote[k] & 3) == 0;
      response.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.D2C_VENUE).rfqId(pendingRfq[k])
          .quoteId(pendingQuote[k]).status(done ? QuoteStatus.Done : QuoteStatus.TradedAway)
          .tradedQuantity(1_000_000_000L).timestampNanos(source.epochNanos());
      response.tradedPrice().mantissa(pendingPrice[k]);
      response.coverPrice().mantissa(done ? pendingPrice[k] : 0L);
      run(RESPONSE, MessageHeaderEncoder.ENCODED_LENGTH + response.encodedLength());
    }
    // idle time: the housekeeping timer runs the hedger and the routers
    for (int t = 0; t < 30; t++) {
      source.advance(10 * MILLI);
      work(TIMER);
      settle();
    }
  }

  private static long percentile(final long[] sample, final int count, final double q) {
    final long[] sorted = Arrays.copyOf(sample, Math.min(count, sample.length));
    Arrays.sort(sorted);
    return sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
  }

  @Test
  void theV2DeskAllocatesNothingOnTheLoopThread() {
    for (int r = 0; r < outbound.length; r++) {
      outbound[r] = RingBufferContract.newOutbound(1 << 20);
    }
    onOrder[AppIds.CLOB_GATEWAY] = (type, b, index, length) -> onOrder(AppIds.CLOB_VENUE, type, b, index);
    onOrder[AppIds.CLOB2_GATEWAY] = (type, b, index, length) -> onOrder(AppIds.CLOB2_VENUE, type, b, index);
    onOrder[AppIds.FUTURES_GATEWAY] = (type, b, index, length) -> onOrder(AppIds.FUTURES_VENUE, type, b, index);
    final DeskProfile profile = DeskProfile.v2().withSettings(DeskProfile.v2().settings().withQuoteRate(1e9));
    final DeskLoopFactory factory = new DeskLoopFactory(profile, universe, FrameSink.NONE);
    loop = factory.create(V2DeskProcess.loopBuilder(new EventClock(source), inbound, outbound));
    desk = factory.last();

    for (int c = 0; c < WARM_UP_CYCLES; c++) {
      cycle();
    }
    rfqTimes = 0;
    repriceTimes = 0;
    Arrays.fill(bytesByKind, 0L);
    final long[] bytes = new long[WINDOWS];
    final long[] threadBytes = new long[WINDOWS];
    for (int w = 0; w < WINDOWS; w++) {
      final long before = threads.getCurrentThreadAllocatedBytes();
      loopBytes = 0;
      for (int c = 0; c < WINDOW_CYCLES; c++) {
        cycle();
      }
      bytes[w] = loopBytes;
      threadBytes[w] = threads.getCurrentThreadAllocatedBytes() - before;
    }
    final StringBuilder byKind = new StringBuilder();
    for (int k = 0; k < KINDS.length; k++) {
      byKind.append(k == 0 ? "" : ", ").append(KINDS[k]).append(' ').append(bytesByKind[k]);
    }

    final var quoting = desk.desk().quoting();
    final var hedger = desk.desk().hedger(CurrencyCode.USD);
    final String report =
        "loop bytes per window " + Arrays.toString(bytes) + " (thread " + Arrays.toString(threadBytes) + "; by cause: " + byKind
            + "); rfqs " + quoting.rfqs() + ", quotes " + quoting.quotesSent()
            + ", fills " + quoting.fills() + ", passes " + quoting.passes() + ", hedge parents " + desk.hedgesRouted()
            + ", hedge fills " + desk.hedgeFills() + ", child orders filled " + ordersFilled + ", passive hedges "
            + hedger.passiveSent() + ", reprices " + desk.pricingV2().reprices() + ", pushed " + desk.repricesPushed()
            + ", leave-one-out references " + desk.pricingV2().leaveOneOutReferences() + ", prints " + desk.tradePrints()
            + ", unfed inputs " + desk.pricingV2().unfedInputs() + ", kill switch " + desk.desk().killSwitch().state();
    System.out.println("wired allocation v2: " + report);
    System.out.println(String.format(Locale.ROOT,
        "loop cost v2: RFQ to quote median %.1f us, 99th percentile %.1f us (%d RFQs); curve round reprice median %.1f us,"
            + " 99th percentile %.1f us (%d rounds)",
        percentile(rfqNanos, rfqTimes, 0.5) / 1e3, percentile(rfqNanos, rfqTimes, 0.99) / 1e3, rfqTimes,
        percentile(repriceNanos, repriceTimes, 0.5) / 1e3, percentile(repriceNanos, repriceTimes, 0.99) / 1e3,
        repriceTimes));

    assertEquals(0, desk.pricingV2().unfedInputs(), report);
    assertTrue(quoting.quotesSent() > WINDOWS * WINDOW_CYCLES, "the desk quoted: " + report);
    assertTrue(quoting.fills() > 0, "RFQs were won: " + report);
    assertTrue(desk.hedgesRouted() > 0, "the hedger routed parents: " + report);
    assertTrue(desk.hedgeFills() > 0, "hedge fills came back: " + report);
    assertTrue(desk.repricesPushed() > WINDOWS * WINDOW_CYCLES, "every round repriced: " + report);
    assertTrue(desk.tradePrints() > WINDOWS * WINDOW_CYCLES, "prints were applied: " + report);
    long fewest = Long.MAX_VALUE;
    for (int w = 1; w < WINDOWS; w++) {
      assertTrue(bytes[w] <= LATER_WINDOW_BOUND, report);
      fewest = Math.min(fewest, bytes[w]);
    }
    assertEquals(0L, fewest, "no window after the first was allocation-free: " + report);
  }
}
