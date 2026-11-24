package io.ratesalgo.cookbook;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskLoopFactory;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.ExecutionReportEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeBustEncoder;
import io.ratesalgo.router.ChildOrderBook;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.router.SmartOrderRouter;
import java.util.Locale;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;

/** COOKBOOK.md recipe 45: a fill that crosses its cancel is booked once. */
public final class Recipe45LateFill {

  private static final long BID = 98_500_000_000L;

  private final ManualClock source = new ManualClock(1_793_626_000_000_000_000L);
  private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final DeskSettings settings = DeskSettings.defaults();
  private final InboundPublisher publisher;
  private final EventLoop loop;
  private final DeskGroup group;
  private final SmartOrderRouter router;
  private final int bond;
  private final long ask;

  private Recipe45LateFill() {
    final UsdUniverse universe = UsdUniverse.standard();
    final ManyToOneRingBuffer ring = RingBufferContract.newInbound(1 << 16);
    publisher = new InboundPublisher(ring);
    final DeskLoopFactory factory = new DeskLoopFactory(universe, settings, FrameSink.NONE);
    loop =
        factory.create(
            EventLoop.builder(new EventClock(source))
                .inbound(ring)
                .outbound(RingBufferContract.newOutbound(1 << 20))
                .outbound(RingBufferContract.newOutbound(1 << 20))
                .outbound(RingBufferContract.newOutbound(1 << 20)));
    group = factory.last();
    router = group.router(0);
    bond = group.routerInstrument(0);
    ask = BID + universe.bondTick();
  }

  public static void main(final String[] args) {
    new Recipe45LateFill().run();
  }

  private void run() {
    final long lot = settings.hedgeLotMinor();
    level(1, Side.Buy, BID);
    level(2, Side.Sell, ask);

    // parent order 7: the router sends one child to the CLOB
    router.submit(7, bond, Side.Buy, lot, 1.0, 0L);
    final long child = onlyChild(router.children());
    report(child, 501, OrdStatus.New, 0);
    print("acknowledged");
    router.cancel(7);
    report(child, 502, OrdStatus.Cancelled, 0);
    print("cancel confirmed");

    // the venue had traded the order before it confirmed the cancel, and says so now
    report(child, 503, OrdStatus.Filled, lot);
    print("late fill 503");
    // the same execution again, as a venue resends it after a reconnect
    report(child, 503, OrdStatus.Filled, lot);
    print("503 resent");
    // the venue takes the trade back
    bust(child, 503, lot);
    print("503 busted");
  }

  private void print(final String step) {
    System.out.printf(
        Locale.ROOT, "%-16s parent active %-5s  position %d lot(s)  late fills %d  unrouted reports %d  busts %d%n",
        step, router.isActive(7), group.desk().risk().position(bond) / settings.hedgeLotMinor(),
        group.lateFills(), group.unroutedReports(), group.venueBusts());
  }

  private static long onlyChild(final ChildOrderBook children) {
    for (int s = 0; s < children.highWater(); s++) {
      if (children.inUse(s)) {
        return children.clOrdId(s);
      }
    }
    throw new IllegalStateException("no child was sent");
  }

  // the messages below are the ones a venue gateway publishes to the loop's inbound ring

  private void send(final int length) {
    if (!publisher.offer(buffer, 0, length)) {
      throw new IllegalStateException("inbound ring full");
    }
    source.advance(1_000_000L);
    loop.doWork();
  }

  private void level(final long sequence, final Side side, final long price) {
    final BookUpdateEncoder e = new BookUpdateEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.CLOB_VENUE).instrumentId(bond).venueSeqNo(sequence)
        .side(side).action(BookAction.Add).level((short) 1).quantity(50 * settings.hedgeLotMinor()).orderCount(1)
        .timestampNanos(source.epochNanos());
    e.price().mantissa(price);
    send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }

  private void report(final long clOrdId, final long execId, final OrdStatus status, final long quantity) {
    final ExecutionReportEncoder e = new ExecutionReportEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.CLOB_VENUE).clOrdId(clOrdId).venueOrderId(1)
        .execId(execId).instrumentId(bond).side(Side.Buy).ordStatus(status).lastQuantity(quantity).leavesQuantity(0)
        .cumQuantity(quantity).transactNanos(source.epochNanos());
    e.lastPrice().mantissa(quantity > 0 ? ask : 0L);
    send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }

  private void bust(final long clOrdId, final long execId, final long quantity) {
    final TradeBustEncoder e = new TradeBustEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.CLOB_VENUE).clOrdId(clOrdId).execId(execId)
        .instrumentId(bond).side(Side.Buy).quantity(quantity).timestampNanos(source.epochNanos());
    e.price().mantissa(ask);
    send(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }
}
