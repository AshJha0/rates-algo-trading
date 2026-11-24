package io.ratesalgo.app.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.ChildState;
import io.ratesalgo.messages.sbe.ExecutionReportEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrderCancelRejectEncoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeBustEncoder;
import io.ratesalgo.messages.sbe.VenueExclusion;
import io.ratesalgo.router.ChildOrderBook;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.router.SmartOrderRouter;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The desk's handling of what venues and the feed can do wrong: a refused cancel, a gapped book, a
 * bust sent twice or for a trade it never had, a fill above the order. Messages go through the
 * loop's inbound ring exactly as a gateway would publish them.
 */
class DeskGroupTest {

  private static final UsdUniverse UNIVERSE = UsdUniverse.standard();
  private static final int CLOB = AppIds.CLOB_VENUE;
  private static final long BID = 98_500_000_000L;

  private final ManualClock source = new ManualClock(1_793_626_000_000_000_000L);
  private final ManyToOneRingBuffer ring = RingBufferContract.newInbound(1 << 16);
  private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final InboundPublisher publisher = new InboundPublisher(ring);
  private final DeskSettings settings = DeskSettings.defaults();
  private EventLoop loop;
  private DeskGroup group;
  private SmartOrderRouter router;
  private int bond;
  private long lot;
  private long tick;
  private long feedSeq;
  private long execId = 500;

  @BeforeEach
  void desk() {
    final DeskLoopFactory factory = new DeskLoopFactory(UNIVERSE, settings, FrameSink.NONE);
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
    lot = settings.hedgeLotMinor();
    tick = UNIVERSE.bondTick();
  }

  private void run(final int length) {
    assertTrue(publisher.offer(buffer, 0, length));
    source.advance(1_000_000L);
    loop.doWork();
  }

  private void level(final long seq, final Side side, final BookAction action, final long price) {
    final BookUpdateEncoder e = new BookUpdateEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(CLOB).instrumentId(bond).venueSeqNo(seq).side(side).action(action)
        .level((short) 1).quantity(50 * lot).orderCount(1).timestampNanos(source.epochNanos());
    e.price().mantissa(price);
    run(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }

  /** A two-sided CLOB book for the bond, sequences 1 and 2. */
  private void book() {
    level(++feedSeq, Side.Buy, BookAction.Add, BID);
    level(++feedSeq, Side.Sell, BookAction.Add, BID + tick);
  }

  private void feedStatus(final byte status) {
    run(ControlMessages.encodeGatewayStatus(buffer, 0, source.epochNanos(), AppIds.CLOB_FEED_STATUS, status));
  }

  private void report(final long clOrdId, final OrdStatus status, final long lastQty, final long cumQty) {
    final ExecutionReportEncoder e = new ExecutionReportEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(CLOB).clOrdId(clOrdId).venueOrderId(1).execId(++execId)
        .instrumentId(bond).side(Side.Buy).ordStatus(status).lastQuantity(lastQty).leavesQuantity(0)
        .cumQuantity(cumQty).transactNanos(source.epochNanos());
    e.lastPrice().mantissa(lastQty > 0 ? BID + tick : 0);
    run(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }

  private void bust(final long clOrdId, final long busted, final long quantity) {
    final TradeBustEncoder e = new TradeBustEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(CLOB).clOrdId(clOrdId).execId(busted).instrumentId(bond)
        .side(Side.Buy).quantity(quantity).timestampNanos(source.epochNanos());
    e.price().mantissa(BID + tick);
    run(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }

  /** The id of the router's only child in the book. */
  private long onlyChild() {
    final ChildOrderBook children = router.children();
    long id = -1;
    for (int s = 0; s < children.highWater(); s++) {
      if (children.inUse(s)) {
        assertEquals(-1, id, "more than one child");
        id = children.clOrdId(s);
      }
    }
    assertTrue(id >= 0, "no child was sent");
    return id;
  }

  private ChildState stateOf(final long child) {
    return router.children().state(router.children().slotOf(child));
  }

  @Test
  void aCancelRejectReachesTheRouterAndAnUnansweredChildEndsItsParent() {
    book();
    router.submit(7, bond, Side.Buy, lot, 1.0, 0L);
    final long child = onlyChild();
    report(child, OrdStatus.New, 0, 0);
    assertEquals(ChildState.Acknowledged, stateOf(child));
    assertTrue(router.cancel(7));
    assertEquals(ChildState.CancelRequested, stateOf(child));

    final OrderCancelRejectEncoder e = new OrderCancelRejectEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(CLOB).clOrdId(child | AppIds.CANCEL_FLAG).origClOrdId(child)
        .instrumentId(bond).ordStatus(OrdStatus.New).responseTo((short) 1).timestampNanos(source.epochNanos());
    final int length = MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength();
    run(length);

    assertEquals(1, group.cancelRejects());
    assertEquals(ChildState.Acknowledged, stateOf(child), "the refused cancel put the child back");
    assertTrue(router.isActive(7));

    // a reject for a cancel the quoting engine sent, and one for a router that does not exist
    e.clOrdId(12_345L).origClOrdId(12_344L);
    run(length);
    e.clOrdId((AppIds.ROUTER_BASE + (99L << 40)) | AppIds.CANCEL_FLAG);
    run(length);
    assertEquals(3, group.cancelRejects());
    assertEquals(1, group.unroutedReports());
    assertEquals(ChildState.Acknowledged, stateOf(child));

    // the venue then says nothing at all: the child is chased a bounded number of times and the
    // parent ends, instead of sitting in the router for the rest of the day
    for (int i = 0; i < 12 && router.isActive(7); i++) {
      source.advance(1_000_000_000L);
      loop.doWork();
    }
    assertFalse(router.isActive(7), "the parent hung");
    assertEquals(1, group.hedgesFailed());
    assertEquals(1, router.unresolvedChildren());
    assertEquals(0, group.hedgesExpired());
  }

  @Test
  void aGappedFeedStopsTheRoutersUntilTheSnapshotIsComplete() {
    book();
    assertTrue(group.clobUsable());
    assertTrue(router.venueUsable(CLOB));

    feedStatus(ControlMessages.STATUS_DISCONNECTED);

    assertFalse(group.clobUsable());
    assertFalse(router.venueUsable(CLOB));
    assertEquals(1, group.feedGaps());
    assertFalse(group.desk().health().healthy(source.epochNanos()), "quoting is pulled for the gap");
    router.submit(8, bond, Side.Buy, lot, 1.0, 0L);
    assertEquals(0, router.childrenSent(), "nothing is routed off a book that is not whole");
    assertEquals(VenueExclusion.Stale, router.lastDecision().exclusion(0));

    // the snapshot: a Clear and the levels, all at the snapshot's sequence; still not whole
    feedSeq = 9;
    level(feedSeq, Side.Buy, BookAction.Clear, 0);
    level(feedSeq, Side.Buy, BookAction.Add, BID);
    level(feedSeq, Side.Sell, BookAction.Add, BID + tick);
    assertFalse(group.clobUsable());
    assertEquals(0, router.childrenSent());

    feedStatus(ControlMessages.STATUS_CONNECTED);

    assertTrue(group.clobUsable());
    assertTrue(router.venueUsable(CLOB));
    assertTrue(group.desk().health().healthy(source.epochNanos()), "the gap is cleared, quoting may resume");
    // the feed carries on from the snapshot's sequence without a false gap
    level(++feedSeq, Side.Buy, BookAction.Change, BID);
    assertTrue(group.clobUsable());
    assertTrue(group.desk().health().healthy(source.epochNanos()));
    source.advance(600_000_000L);
    loop.doWork();
    assertTrue(router.childrenSent() > 0, "the waiting parent is routed once the book is whole");
    assertEquals(1, group.feedGaps());
  }

  @Test
  void aJumpInTheFeedSequenceAloneMarksTheBookGapped() {
    book();
    level(feedSeq + 5, Side.Buy, BookAction.Change, BID);
    assertFalse(group.clobUsable());
    assertFalse(router.venueUsable(CLOB));
    assertEquals(1, group.feedGaps());
    feedStatus(ControlMessages.STATUS_CONNECTED);
    assertTrue(group.clobUsable());
  }

  @Test
  void aLostClobSessionStopsTheRoutersToo() {
    book();
    run(ControlMessages.encodeGatewayStatus(buffer, 0, source.epochNanos(), AppIds.CLOB_GATEWAY, ControlMessages.STATUS_DISCONNECTED));
    assertFalse(group.clobUsable());
    assertFalse(router.venueUsable(CLOB));
    run(ControlMessages.encodeGatewayStatus(buffer, 0, source.epochNanos(), AppIds.CLOB_GATEWAY, ControlMessages.STATUS_CONNECTED));
    assertTrue(group.clobUsable());
    assertTrue(router.venueUsable(CLOB));
  }

  @Test
  void aTradeBustReversesRiskOnceAndOnlyForATradeTheDeskBooked() {
    book();
    router.submit(9, bond, Side.Buy, 2 * lot, 1.0, 0L);
    final long child = onlyChild();
    report(child, OrdStatus.Filled, 2 * lot, 2 * lot);
    final long filled = execId;
    assertEquals(2 * lot, group.desk().risk().position(bond));

    bust(child, filled, 2 * lot);
    assertEquals(1, group.venueBusts());
    assertEquals(0, group.desk().risk().position(bond), "the bust reverses the fill");

    bust(child, filled, 2 * lot);
    assertEquals(1, group.venueBusts());
    assertEquals(1, group.duplicateBusts());
    assertEquals(0, group.desk().risk().position(bond), "the same bust again reverses nothing");

    bust(child, 987_654L, 2 * lot);
    assertEquals(1, group.venueBusts());
    assertEquals(1, group.unknownBusts());
    assertEquals(0, group.desk().risk().position(bond), "a bust for a trade never booked invents no position");
  }

  @Test
  void aFillAboveTheOrderedQuantityIsBookedInFullAndCounted() {
    book();
    router.submit(10, bond, Side.Buy, lot, 1.0, 0L);
    final long child = onlyChild();
    report(child, OrdStatus.Filled, 3 * lot, 3 * lot);
    assertEquals(3 * lot, group.desk().risk().position(bond), "the position is what the venue traded");
    assertEquals(1, group.hedgeFills());
    assertEquals(1, group.venueOverfills());
    assertEquals(2 * lot, group.venueOverfillQuantity());
    assertFalse(router.isActive(10));
  }

  private void clobSession(final byte status) {
    run(ControlMessages.encodeGatewayStatus(buffer, 0, source.epochNanos(), AppIds.CLOB_GATEWAY, status));
  }

  @Test
  void aReturningClobSessionStaysDownUntilItsOrdersAreReconciled() {
    book();
    router.submit(9, bond, Side.Buy, 2 * lot, 1.0, 0L);
    final long child = onlyChild();
    report(child, OrdStatus.New, 0, 0);
    clobSession(ControlMessages.STATUS_DISCONNECTED);
    assertFalse(group.clobUsable());

    // logged on again: not usable yet, the venue is being asked what it holds
    clobSession(ControlMessages.STATUS_RECONCILING);
    assertTrue(group.reconciling());
    assertFalse(group.clobUsable(), "RECONCILING is not CONNECTED");
    assertFalse(router.venueUsable(CLOB));
    assertFalse(group.desk().health().healthy(source.epochNanos()), "quoting stays pulled while reconciling");
    // the venue's answer: nothing of ours, but an order under a router id that no router holds
    final long orphan = child + 77;
    report(orphan, OrdStatus.New, 0, 0);
    assertEquals(0, group.reconciliations());

    clobSession(ControlMessages.STATUS_RECONCILED);
    assertFalse(group.reconciling());
    assertEquals(1, group.reconciliations());
    assertTrue(group.clobUsable(), "RECONCILED brings the venue up");
    assertTrue(router.venueUsable(CLOB));
    assertEquals(group.reconcileChecked(), group.reconcileMissing(), "the venue listed none of the router's children");
    assertEquals(1, group.reconcileOrphans(), "the order the venue works and no router holds was cancelled");
    assertEquals(0, group.reconcileReleased(), "absence from the list frees nothing by itself");

    // a drop in the middle of an answer throws the half-collected list away
    clobSession(ControlMessages.STATUS_RECONCILING);
    report(orphan, OrdStatus.New, 0, 0);
    clobSession(ControlMessages.STATUS_DISCONNECTED);
    assertFalse(group.reconciling());
    clobSession(ControlMessages.STATUS_RECONCILED);
    assertEquals(1, group.reconciliations(), "a RECONCILED with no open window reconciles nothing");
    assertEquals(1, group.reconcileOrphans());
  }

  @Test
  void aFillOfAChildTheRouterNoLongerHoldsIsStillAPositionBookedOnce() {
    book();
    router.submit(11, bond, Side.Buy, lot, 1.0, 0L);
    final long released = onlyChild() + 77;
    final long before = group.desk().risk().position(bond);
    report(released, OrdStatus.Cancelled, 0, 0);
    assertEquals(1, group.unroutedReports());
    assertEquals(0, group.lateFills(), "a report without a fill books nothing");
    assertEquals(before, group.desk().risk().position(bond));

    report(released, OrdStatus.Filled, lot, lot);
    assertEquals(1, group.lateFills());
    assertEquals(before + lot, group.desk().risk().position(bond), "what the venue traded is our position");

    // the venue resends it after a reconnect: same venue, same execution id
    execId--;
    report(released, OrdStatus.Filled, lot, lot);
    assertEquals(1, group.lateFills());
    assertEquals(before + lot, group.desk().risk().position(bond));
    assertEquals(3, group.unroutedReports());

    // a bust of that execution takes the position back
    bust(released, execId, lot);
    assertEquals(before, group.desk().risk().position(bond));
  }

  @Test
  void aFillOnTheQuotingEnginesOwnOrderIsShownToItOnce() {
    book();
    final long ownOrder = 4_242L; // not a router id
    assertFalse(AppIds.isRouterOrder(ownOrder));
    report(ownOrder, OrdStatus.Filled, lot, lot);
    assertEquals(0, group.duplicateFills());
    final long digest = group.stateDigest(UNIVERSE.instrumentIds());
    // the venue resends the fill after a reconnect: same venue, same execution id
    execId--;
    report(ownOrder, OrdStatus.Filled, lot, lot);
    assertEquals(1, group.duplicateFills());
    assertEquals(digest, group.stateDigest(UNIVERSE.instrumentIds()), "the repeat changed nothing");
    // a different execution id is a different fill
    report(ownOrder, OrdStatus.Filled, lot, 2 * lot);
    assertEquals(1, group.duplicateFills());
  }

  @Test
  void anEmptyRebuildSeatsTheFeedOnTheNextFrameWhateverItsNumber() {
    book();
    assertTrue(group.clobUsable());
    // the venue restarted with an empty book: a rebuild with no frame, then numbers from the start
    feedStatus(ControlMessages.STATUS_DISCONNECTED);
    assertFalse(group.clobUsable());
    feedStatus(ControlMessages.STATUS_CONNECTED);
    assertTrue(group.clobUsable());
    assertTrue(group.desk().health().healthy(source.epochNanos()));
    final long gaps = group.feedGaps();
    level(1, Side.Buy, BookAction.Add, BID);
    level(2, Side.Sell, BookAction.Add, BID + tick);
    assertEquals(gaps, group.feedGaps(), "a new run of sequence numbers after a rebuild is not a gap");
    assertTrue(group.clobUsable());
    assertTrue(group.desk().health().healthy(source.epochNanos()));
  }
}
