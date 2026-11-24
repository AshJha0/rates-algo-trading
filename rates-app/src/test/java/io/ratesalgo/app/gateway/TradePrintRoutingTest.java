package io.ratesalgo.app.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.pricing.V2InputIds;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradePrintDecoder;
import io.ratesalgo.messages.sbe.TradePrintEncoder;
import io.ratesalgo.venues.feed.BookFeedPublisher;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import java.util.ArrayList;
import java.util.List;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.junit.jupiter.api.Test;

/**
 * Trade prints and curve-only inputs on their way into the loops: the feed binding forwards a
 * print without letting it touch the book's delta sequence, and the inbound router sends a print
 * to its instrument's group and a fixing or futures quote of the curve to every group.
 */
class TradePrintRoutingTest {

  private static final int VENUE = AppIds.CLOB2_VENUE;
  private static final int BOND = 1001;

  private final ManualClock clock = new ManualClock(1_793_626_000_000_000_000L);
  private final InProcessFeedChannel channel = new InProcessFeedChannel();
  private final BookFeedPublisher publisher = new BookFeedPublisher(VENUE, channel, clock);
  private final List<Integer> templates = new ArrayList<>();
  private boolean full;

  private FeedBinding binding() {
    return new FeedBinding(
        channel, publisher,
        (buffer, offset, length) -> {
          if (full) {
            return false;
          }
          templates.add(RingBufferContract.templateId(buffer, offset));
          return true;
        },
        AppIds.CLOB2_FEED_STATUS, clock);
  }

  @Test
  void aPrintIsForwardedInOrderAndIsNoPartOfTheDeltaSequence() {
    final FeedBinding feed = binding();
    publisher.level(BOND, Side.Buy, 99_000_000_000L, 5_000_000, 1);
    feed.doWork();
    assertTrue(feed.synced());
    final long rebuilds = feed.rebuilds();
    templates.clear();

    publisher.trade(BOND, Side.Sell, 99_000_000_000L, 1_000_000);
    publisher.level(BOND, Side.Buy, 99_000_000_000L, 4_000_000, 1);
    publisher.trade(BOND, Side.Sell, 99_000_000_000L, 2_000_000);
    publisher.level(BOND, Side.Buy, 99_000_000_000L, 2_000_000, 1);
    feed.doWork();

    assertEquals(List.of(TradePrintDecoder.TEMPLATE_ID, 2, TradePrintDecoder.TEMPLATE_ID, 2), templates,
        "the print before the delta of its trade");
    assertEquals(2, feed.prints());
    assertEquals(2, feed.frames());
    assertEquals(0, feed.gaps(), "two prints between two deltas are not a gap");
    assertEquals(rebuilds, feed.rebuilds());
    assertTrue(feed.synced());
  }

  @Test
  void aPrintTheLoopCannotTakeIsCountedAndTheBookStaysWhole() {
    final FeedBinding feed = binding();
    publisher.level(BOND, Side.Buy, 99_000_000_000L, 5_000_000, 1);
    feed.doWork();
    final long rebuilds = feed.rebuilds();
    full = true;
    publisher.trade(BOND, Side.Sell, 99_000_000_000L, 1_000_000);
    feed.doWork();
    assertEquals(1, feed.printsDropped());
    assertEquals(0, feed.prints());
    assertTrue(feed.synced(), "a lost print loses no level");
    assertEquals(0, feed.undelivered());
    assertEquals(rebuilds, feed.rebuilds());

    // a delta the loop cannot take is a gap, as before; prints that arrive while the book is
    // being rebuilt are passed over
    publisher.level(BOND, Side.Buy, 99_000_000_000L, 4_000_000, 1);
    publisher.trade(BOND, Side.Sell, 99_000_000_000L, 1_000_000);
    feed.doWork();
    assertFalse(feed.synced());
    assertEquals(1, feed.undelivered());
    assertEquals(2, feed.printsDropped());
    full = false;
    feed.doWork();
    assertTrue(feed.synced());
    publisher.trade(BOND, Side.Buy, 99_100_000_000L, 1_000_000);
    feed.doWork();
    assertEquals(1, feed.prints());
  }

  @Test
  void theRouterSendsAPrintToItsGroupAndACurveOnlyInputToEveryGroup() {
    final ManyToOneRingBuffer usd = RingBufferContract.newInbound(1 << 13);
    final ManyToOneRingBuffer other = RingBufferContract.newInbound(1 << 13);
    final Int2IntHashMap groupOf = new Int2IntHashMap(-1);
    groupOf.put(BOND, 0);
    groupOf.put(2001, 1);
    final InboundRouter router = new InboundRouter(new RingBuffer[] {usd, other}, groupOf);
    final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
    final MessageHeaderEncoder header = new MessageHeaderEncoder();

    final TradePrintEncoder print = new TradePrintEncoder();
    print.wrapAndApplyHeader(buffer, 0, header).venueId(VENUE).instrumentId(BOND).venueSeqNo(1).aggressorSide(Side.Buy)
        .quantity(100_000_000L).timestampNanos(clock.epochNanos());
    print.price().mantissa(99_000_000_000L);
    final int printLength = MessageHeaderEncoder.ENCODED_LENGTH + print.encodedLength();
    assertEquals(InboundRouter.DELIVERED, router.route(buffer, 0, printLength));
    assertEquals(1, usd.read((type, b, index, length) -> assertEquals(TradePrintDecoder.TEMPLATE_ID, type), 8));
    assertEquals(0, other.size(), "a print goes to its instrument's group only");
    print.instrumentId(77);
    assertEquals(InboundRouter.UNROUTED, router.route(buffer, 0, printLength));
    assertEquals(1, router.unrouted());

    final CurveInputEncoder curve = new CurveInputEncoder();
    for (final int id : new int[] {V2InputIds.OVERNIGHT, V2InputIds.sr1(202612), V2InputIds.sr3(202703), V2InputIds.parSwap(36)}) {
      curve.wrapAndApplyHeader(buffer, 0, header).curveId(AppIds.SOFR_CURVE).kind(CurveInputKind.Future).instrumentId(id)
          .tenor("    ").timestampNanos(clock.epochNanos());
      curve.value().mantissa(96_400_000_000L);
      final int length = MessageHeaderEncoder.ENCODED_LENGTH + curve.encodedLength();
      assertEquals(InboundRouter.DELIVERED, router.route(buffer, 0, length), "input " + id);
      assertEquals(1, usd.read((type, b, index, len) -> { }, 8));
      assertEquals(1, other.read((type, b, index, len) -> { }, 8), "every group prices off the curve");
    }
    // a par swap that is a group's instrument still goes to that group alone
    curve.wrapAndApplyHeader(buffer, 0, header).curveId(AppIds.SOFR_CURVE).kind(CurveInputKind.ParSwap).instrumentId(2001)
        .tenor("2Y  ").timestampNanos(clock.epochNanos());
    assertEquals(InboundRouter.DELIVERED,
        router.route(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + curve.encodedLength()));
    assertEquals(0, usd.size());
    assertEquals(1, other.read((type, b, index, len) -> { }, 8));
  }

  @Test
  void aPrintOnAFullRingIsMarketDataNotASessionMessage() {
    final ManyToOneRingBuffer small = RingBufferContract.newInbound(1 << 13);
    final Int2IntHashMap groupOf = new Int2IntHashMap(-1);
    groupOf.put(BOND, 0);
    final InboundRouter router = new InboundRouter(new RingBuffer[] {small}, groupOf);
    final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
    final TradePrintEncoder print = new TradePrintEncoder();
    print.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder()).venueId(VENUE).instrumentId(BOND).venueSeqNo(1)
        .aggressorSide(Side.Buy).quantity(100_000_000L).timestampNanos(clock.epochNanos());
    print.price().mantissa(99_000_000_000L);
    final int length = MessageHeaderEncoder.ENCODED_LENGTH + print.encodedLength();
    int result = InboundRouter.DELIVERED;
    for (int i = 0; i < 1_000 && result == InboundRouter.DELIVERED; i++) {
      result = router.route(buffer, 0, length);
    }
    assertEquals(InboundRouter.FULL, result);
    assertEquals(1, router.dropped(), "dropped at once and counted");
    assertEquals(0, router.retries(), "no spinning for a print");
    assertEquals(0, router.undelivered());
  }

  @Test
  void theCurveOnlyIdsAreTheOnesAboveTheReferenceData() {
    assertTrue(V2InputIds.isCurveOnly(V2InputIds.OVERNIGHT));
    assertTrue(V2InputIds.isCurveOnly(V2InputIds.parSwap(12)));
    assertTrue(V2InputIds.isCurveOnly(V2InputIds.sr1(202612)));
    assertTrue(V2InputIds.isCurveOnly(V2InputIds.sr3(202706)));
    assertFalse(V2InputIds.isCurveOnly(2001));
    assertFalse(V2InputIds.isCurveOnly(AppIds.SWAP_1Y));
    assertFalse(V2InputIds.isCurveOnly(5_000_000));
    assertTrue(V2InputIds.isSr1(V2InputIds.sr1(202612)) && !V2InputIds.isSr3(V2InputIds.sr1(202612)));
    assertTrue(V2InputIds.isSr3(V2InputIds.sr3(202612)) && !V2InputIds.isSr1(V2InputIds.sr3(202612)));
    assertFalse(V2InputIds.isSr1(V2InputIds.parSwap(120)) || V2InputIds.isSr3(V2InputIds.OVERNIGHT));
    assertEquals(120, V2InputIds.parSwapMonths(V2InputIds.parSwap(120)));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> V2InputIds.parSwapMonths(V2InputIds.sr1(202612)));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> V2InputIds.parSwapMonths(2001));
  }
}
