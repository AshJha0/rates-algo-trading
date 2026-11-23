package io.ratesalgo.app.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.app.pricing.PricingBindings;
import io.ratesalgo.app.pricing.PricingV2;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.app.pricing.V2InputIds;
import io.ratesalgo.app.sim.V2DeskProcess;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.ChildState;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.ExecutionReportEncoder;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.NewOrderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.OrderCancelDecoder;
import io.ratesalgo.messages.sbe.OrderCancelRejectEncoder;
import io.ratesalgo.messages.sbe.RouteDecisionLadderDecoder;
import io.ratesalgo.messages.sbe.RouteDecisionModelDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.messages.sbe.TradeBustEncoder;
import io.ratesalgo.messages.sbe.TradePrintEncoder;
import io.ratesalgo.router.SmartOrderRouter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The v2 desk profile at desk level: three order-book venues, pricing v2, strategy v2 and the
 * ladder router on one loop. Messages go through the loop's inbound ring exactly as the gateways
 * publish them, and what the desk sends is read from its outbound rings. The 1.1.0 rules for the
 * kill switch, a gapped or stale book, a refused cancel and an overfill are checked on every
 * venue, with the others still trading.
 */
class DeskGroupV2Test {

  private static final UsdUniverse UNIVERSE = UsdUniverse.simV2();
  private static final int CLOB = AppIds.CLOB_VENUE;
  private static final int CLOB2 = AppIds.CLOB2_VENUE;
  private static final int FUT = AppIds.FUTURES_VENUE;
  private static final long SECOND = 1_000_000_000L;
  private static final long BOND_BID = 99_500_000_000L;
  private static final long FUT_BID = 110_000_000_000L;
  private static final long MILLION = 100_000_000L;

  private final ManualClock source = new ManualClock(V2DeskProcess.SESSION_OPEN_EPOCH_NANOS + 5_400 * SECOND);
  private final ManyToOneRingBuffer ring = RingBufferContract.newInbound(1 << 18);
  private final OneToOneRingBuffer[] out = new OneToOneRingBuffer[AppIds.V2_OUTBOUND_RINGS];
  private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final InboundPublisher publisher = new InboundPublisher(ring);
  private final Set<Integer> routeTemplates = new HashSet<>();
  private final long[] feedSeq = new long[200];
  private EventLoop loop;
  private DeskGroup group;
  private long execId = 900;

  @TempDir Path dir;

  private void desk(final DeskProfile profile) {
    for (int r = 0; r < out.length; r++) {
      out[r] = RingBufferContract.newOutbound(1 << 20);
    }
    final DeskLoopFactory factory =
        new DeskLoopFactory(
            profile, UNIVERSE, (templateId, version, b, offset, length) -> routeTemplates.add(templateId));
    loop = factory.create(V2DeskProcess.loopBuilder(new EventClock(source), ring, out));
    group = factory.last();
  }

  private void run(final int length) {
    assertTrue(publisher.offer(buffer, 0, length));
    source.advance(1_000_000L);
    loop.doWork();
  }

  private void pass(final long nanos) {
    source.advance(nanos);
    loop.doWork();
  }

  private int bond(final int j) {
    return UNIVERSE.bondIds()[j];
  }

  private int future() {
    return UNIVERSE.futureIds()[0];
  }

  private SmartOrderRouter routerOf(final int instrumentId) {
    for (int j = 0; j < group.routerCount(); j++) {
      if (group.routerInstrument(j) == instrumentId) {
        return group.router(j);
      }
    }
    throw new AssertionError("no router for " + instrumentId);
  }

  private void level(
      final int venue, final int instrument, final long seq, final Side side, final BookAction action, final long price,
      final long quantity) {
    final BookUpdateEncoder e = new BookUpdateEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(venue).instrumentId(instrument).venueSeqNo(seq).side(side)
        .action(action).level((short) 1).quantity(quantity).orderCount(1).timestampNanos(source.epochNanos());
    e.price().mantissa(price);
    run(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }

  /** A two-sided book one tick wide, {@code quantity} a side in the venue's own units. */
  private void book(final int venue, final int instrument, final long bid, final long tick, final long quantity) {
    level(venue, instrument, ++feedSeq[venue], Side.Buy, BookAction.Add, bid, quantity);
    level(venue, instrument, ++feedSeq[venue], Side.Sell, BookAction.Add, bid + tick, quantity);
  }

  private long bondTick(final int instrument) {
    return UNIVERSE.bond(instrument).tick();
  }

  /** Books for one bond on both CLOBs and for the future, 5 million and 50 contracts a side. */
  private void books(final int bond) {
    book(CLOB, bond, BOND_BID, bondTick(bond), 5 * MILLION);
    book(CLOB2, bond, BOND_BID, bondTick(bond), 5 * MILLION);
    book(FUT, future(), FUT_BID, UNIVERSE.future(future()).tick(), 50 * 100L);
  }

  private void status(final int id, final byte status) {
    run(ControlMessages.encodeGatewayStatus(buffer, 0, source.epochNanos(), id, status));
  }

  private void report(
      final int venue, final int instrument, final long clOrdId, final OrdStatus status, final long lastQty,
      final long cumQty, final long price) {
    final ExecutionReportEncoder e = new ExecutionReportEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(venue).clOrdId(clOrdId).venueOrderId(1).execId(++execId)
        .instrumentId(instrument).side(Side.Buy).ordStatus(status).lastQuantity(lastQty).leavesQuantity(0)
        .cumQuantity(cumQty).transactNanos(source.epochNanos());
    e.lastPrice().mantissa(lastQty > 0 ? price : 0);
    run(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }

  private void kill() {
    final KillSwitchEncoder e = new KillSwitchEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).reason(KillReason.Manual).scopeId(0).requestedBy(9001)
        .timestampNanos(source.epochNanos());
    run(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }

  /** New orders published to a gateway since it was last read: {clOrdId, quantity, price, venueId}. */
  private List<long[]> orders(final int gateway) {
    final List<long[]> found = new ArrayList<>();
    final NewOrderDecoder d = new NewOrderDecoder();
    final MessageHeaderDecoder h = new MessageHeaderDecoder();
    out[gateway].read(
        (type, b, index, length) -> {
          if (type == NewOrderDecoder.TEMPLATE_ID) {
            d.wrapAndApplyHeader(b, index, h);
            found.add(new long[] {d.clOrdId(), d.quantity(), d.price().mantissa(), d.venueId()});
          } else if (type == OrderCancelDecoder.TEMPLATE_ID) {
            cancels[gateway]++;
          }
        },
        4096);
    return found;
  }

  private final int[] cancels = new int[AppIds.V2_OUTBOUND_RINGS];

  /** One complete curve round of the binding's own build set, flat at {@code ratePercent}. */
  private void curveRound(final double ratePercent) {
    final PricingV2 pricing = group.pricingV2();
    final long stamp = source.epochNanos();
    for (int i = 0; i < pricing.inputCount(); i++) {
      final int id = pricing.inputId(i);
      final CurveInputKind kind =
          id == V2InputIds.OVERNIGHT
              ? CurveInputKind.Fixing
              : V2InputIds.isSr1(id) || V2InputIds.isSr3(id) ? CurveInputKind.Future : CurveInputKind.ParSwap;
      final double value = kind == CurveInputKind.Future ? 100.0 - ratePercent : ratePercent;
      final CurveInputEncoder e = new CurveInputEncoder();
      e.wrapAndApplyHeader(buffer, 0, header).curveId(AppIds.SOFR_CURVE).kind(kind).instrumentId(id).tenor("    ")
          .timestampNanos(stamp);
      e.value().mantissa(Math.round(value * 1e9));
      run(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }
    final HeartbeatEncoder hb = new HeartbeatEncoder();
    hb.wrapAndApplyHeader(buffer, 0, header).sourceId(AppIds.CURVE_HEARTBEAT_SOURCE).timestampNanos(stamp);
    run(MessageHeaderEncoder.ENCODED_LENGTH + hb.encodedLength());
  }

  @Test
  void theV2ProfileBuildsEveryComponentInItsSecondVersion() {
    desk(DeskProfile.v2());
    assertEquals(3, group.venueCount());
    assertEquals(CLOB, group.venueId(0));
    assertEquals(CLOB2, group.venueId(1));
    assertEquals(FUT, group.venueId(2));
    assertNotNull(group.pricingV2());
    assertNotNull(group.spreadRisk());
    assertThrows(IllegalStateException.class, group::pricing);
    assertEquals(UNIVERSE.bonds().size() + 1, group.routerCount(), "a router per Treasury and one for the future");
    for (int j = 0; j < group.routerCount(); j++) {
      final SmartOrderRouter router = group.router(j);
      assertNotNull(router.model(), "the ladder model");
      final boolean future = group.routerInstrument(j) == future();
      assertEquals(future ? 1 : 2, router.venues().length, "every venue that lists the instrument, not one");
      assertEquals(future ? FUT : CLOB, router.venues()[0].venueId());
      assertEquals(
          future ? UNIVERSE.future(future()).tick() : bondTick(group.routerInstrument(j)),
          router.venues()[0].tickMantissa(), "the instrument's own tick");
      assertEquals(future ? 10_000_000L : MILLION, router.venues()[0].lotSize());
    }
    assertNotNull(group.desk().quoting().cover(), "strategy v2: cover pricing");
    assertNotNull(group.desk().quoting().markouts(), "strategy v2: markout store");
    assertNotNull(group.binding().directory().byId(future()), "the future is an instrument of the desk");
    assertNotNull(group.binding().directory().byId(AppIds.SWAP_1Y));
  }

  @Test
  void theRecordedProfileIsWhatTheSettingsConstructorBuilds() {
    final DeskLoopFactory factory =
        new DeskLoopFactory(UsdUniverse.standard(), DeskSettings.defaults(), io.ratesalgo.router.FrameSink.NONE);
    final EventLoop v1 =
        factory.create(
            EventLoop.builder(new EventClock(source)).inbound(ring)
                .outbound(RingBufferContract.newOutbound(1 << 16)).outbound(RingBufferContract.newOutbound(1 << 16))
                .outbound(RingBufferContract.newOutbound(1 << 16)));
    assertNotNull(v1);
    final DeskGroup recorded = factory.last();
    assertTrue(recorded.profile().isRecorded());
    assertEquals(DeskProfile.defaults(), recorded.profile());
    assertEquals(1, recorded.venueCount());
    assertNotNull(recorded.pricing());
    assertNull(recorded.pricingV2());
    assertNull(recorded.spreadRisk());
    assertNull(recorded.router(0).model(), "the touch model");
    assertEquals(1, recorded.router(0).venues().length);
    assertFalse(recorded.endSession(), "no markout store, no file");
  }

  @Test
  void eachComponentCanBeSwitchedAlone() {
    // pricing v2 and the ladder router on the standard market with the default strategy
    final DeskProfile mixed =
        DeskProfile.defaults().withPricing(PricingBindings.Version.V2).withRouter(DeskProfile.RouterModel.LADDER);
    final DeskLoopFactory factory = new DeskLoopFactory(mixed, mixed.newUniverse(), io.ratesalgo.router.FrameSink.NONE);
    factory.create(
        EventLoop.builder(new EventClock(source)).inbound(ring)
            .outbound(RingBufferContract.newOutbound(1 << 16)).outbound(RingBufferContract.newOutbound(1 << 16))
            .outbound(RingBufferContract.newOutbound(1 << 16)));
    final DeskGroup g = factory.last();
    assertNotNull(g.pricingV2());
    assertNotNull(g.router(0).model());
    assertEquals(1, g.venueCount());
    assertNull(g.desk().quoting().cover(), "the default strategy");

    // the v2 market with version 1 of everything else
    desk(DeskProfile.defaults().withMarket(DeskProfile.Market.SIM_V2));
    assertNotNull(group.pricing());
    assertNull(group.router(0).model());
    assertEquals(3, group.venueCount());
    assertNull(group.desk().quoting().cover());
  }

  @Test
  void aKillWithdrawsOnEveryVenue() {
    desk(DeskProfile.v2());
    final int bond = bond(2);
    books(bond);
    // more than either CLOB shows at the touch, so the sweep sends a child to each
    routerOf(bond).submit(11, bond, Side.Buy, 8 * MILLION, 1.0, BOND_BID + 4 * bondTick(bond));
    routerOf(future()).submit(12, future(), Side.Buy, 3 * 10_000_000L, 1.0, FUT_BID + 4 * UNIVERSE.future(future()).tick());
    final List<long[]> clob = orders(AppIds.CLOB_GATEWAY);
    final List<long[]> clob2 = orders(AppIds.CLOB2_GATEWAY);
    final List<long[]> fut = orders(AppIds.FUTURES_GATEWAY);
    assertFalse(clob.isEmpty(), "a child on the first CLOB");
    assertFalse(clob2.isEmpty(), "a child on the second CLOB");
    assertEquals(1, fut.size(), "a child on the futures venue");
    assertEquals(CLOB2, clob2.get(0)[3]);
    assertEquals(3 * 100L, fut.get(0)[1], "the futures venue is sent contracts, not face");
    // every venue acknowledges; the children rest working
    for (final long[] o : clob) {
      report(CLOB, bond, o[0], OrdStatus.New, 0, 0, 0);
    }
    for (final long[] o : clob2) {
      report(CLOB2, bond, o[0], OrdStatus.New, 0, 0, 0);
    }
    report(FUT, future(), fut.get(0)[0], OrdStatus.New, 0, 0, 0);
    final int live = group.liveChildren();
    assertTrue(live >= 3, "children live on three venues: " + live);

    kill();

    assertFalse(group.desk().killSwitch().isActive(), "the kill switch is pulled");
    orders(AppIds.CLOB_GATEWAY);
    orders(AppIds.CLOB2_GATEWAY);
    orders(AppIds.FUTURES_GATEWAY);
    assertTrue(cancels[AppIds.CLOB_GATEWAY] >= clob.size(), "cancels to the first CLOB");
    assertTrue(cancels[AppIds.CLOB2_GATEWAY] >= clob2.size(), "cancels to the second CLOB");
    assertTrue(cancels[AppIds.FUTURES_GATEWAY] >= 1, "a cancel to the futures venue");
    // the venues confirm: nothing of ours is left anywhere
    for (final long[] o : clob) {
      report(CLOB, bond, o[0], OrdStatus.Cancelled, 0, 0, 0);
    }
    for (final long[] o : clob2) {
      report(CLOB2, bond, o[0], OrdStatus.Cancelled, 0, 0, 0);
    }
    report(FUT, future(), fut.get(0)[0], OrdStatus.Cancelled, 0, 0, 0);
    assertEquals(0, group.liveChildren(), "router children working after the kill");
    assertEquals(0, group.desk().quoting().liveQuotes());
  }

  @Test
  void aGappedVenueIsExcludedAndTheOthersKeepTrading() {
    desk(DeskProfile.v2());
    final int bond = bond(2);
    books(bond);
    final SmartOrderRouter router = routerOf(bond);
    assertTrue(group.venueUsable(CLOB) && group.venueUsable(CLOB2) && group.venueUsable(FUT));
    assertTrue(group.hedgeAvailable(bond) && group.hedgeAvailable(future()));

    // a jump in the first CLOB's own sequence: that venue is gapped, the others are not
    feedSeq[CLOB] += 5;
    level(CLOB, bond, feedSeq[CLOB], Side.Buy, BookAction.Change, BOND_BID, 4 * MILLION);

    assertFalse(group.venueUsable(CLOB));
    assertFalse(router.venueUsable(CLOB));
    assertTrue(group.venueUsable(CLOB2) && router.venueUsable(CLOB2));
    assertTrue(group.venueUsable(FUT) && routerOf(future()).venueUsable(FUT));
    assertEquals(1, group.feedGaps());
    assertFalse(group.desk().health().healthy(source.epochNanos()), "quoting is pulled while a book is gapped");
    assertTrue(group.hedgeAvailable(bond), "the bond still trades on the second CLOB");

    router.submit(21, bond, Side.Buy, 2 * MILLION, 1.0, BOND_BID + 4 * bondTick(bond));
    assertTrue(orders(AppIds.CLOB_GATEWAY).isEmpty(), "nothing is routed to the gapped venue");
    final List<long[]> second = orders(AppIds.CLOB2_GATEWAY);
    assertFalse(second.isEmpty(), "the other CLOB takes the order");
    long sent = 0;
    for (final long[] o : second) {
      sent += o[1];
    }
    assertEquals(2 * MILLION, sent);

    // the futures feed announces a rebuild: the future stops being a hedge, the bonds do not
    status(AppIds.FUTURES_FEED_STATUS, ControlMessages.STATUS_DISCONNECTED);
    assertFalse(group.venueUsable(FUT));
    assertFalse(group.hedgeAvailable(future()));
    assertTrue(group.hedgeAvailable(bond));
    assertEquals(2, group.feedGaps());
    status(AppIds.FUTURES_FEED_STATUS, ControlMessages.STATUS_CONNECTED);
    assertTrue(group.venueUsable(FUT));
    assertTrue(group.hedgeAvailable(future()));

    // the first CLOB's snapshot and CONNECTED bring it back
    status(AppIds.CLOB_FEED_STATUS, ControlMessages.STATUS_DISCONNECTED);
    feedSeq[CLOB] = 40;
    level(CLOB, bond, 40, Side.Buy, BookAction.Clear, 0, 0);
    level(CLOB, bond, 40, Side.Buy, BookAction.Add, BOND_BID, 5 * MILLION);
    level(CLOB, bond, 40, Side.Sell, BookAction.Add, BOND_BID + bondTick(bond), 5 * MILLION);
    assertFalse(group.venueUsable(CLOB), "not whole until the feed says so");
    status(AppIds.CLOB_FEED_STATUS, ControlMessages.STATUS_CONNECTED);
    assertTrue(group.venueUsable(CLOB) && router.venueUsable(CLOB));
  }

  @Test
  void theFirstSnapshotOfABookIsNotAGap() {
    desk(DeskProfile.v2());
    final int bond = bond(2);
    // a venue that was trading before the desk connected: DISCONNECTED, the snapshot, CONNECTED
    status(AppIds.CLOB2_FEED_STATUS, ControlMessages.STATUS_DISCONNECTED);
    assertFalse(group.venueUsable(CLOB2));
    assertEquals(0, group.feedGaps(), "there was no book to lose");
    level(CLOB2, bond, 700, Side.Buy, BookAction.Clear, 0, 0);
    level(CLOB2, bond, 700, Side.Buy, BookAction.Add, BOND_BID, 5 * MILLION);
    level(CLOB2, bond, 700, Side.Sell, BookAction.Add, BOND_BID + bondTick(bond), 5 * MILLION);
    status(AppIds.CLOB2_FEED_STATUS, ControlMessages.STATUS_CONNECTED);
    assertTrue(group.venueUsable(CLOB2));
    assertEquals(0, group.feedGaps());
    assertTrue(routerOf(bond).venueUsable(CLOB2));
    level(CLOB2, bond, 701, Side.Buy, BookAction.Change, BOND_BID, 4 * MILLION);
    assertTrue(group.venueUsable(CLOB2), "the feed carries on from the snapshot's sequence");
    // a later DISCONNECTED is a gap
    status(AppIds.CLOB2_FEED_STATUS, ControlMessages.STATUS_DISCONNECTED);
    assertEquals(1, group.feedGaps());
  }

  @Test
  void aSessionDownOnOneVenueLeavesTheOthersUp() {
    desk(DeskProfile.v2());
    final int bond = bond(1);
    books(bond);
    status(AppIds.CLOB2_GATEWAY, ControlMessages.STATUS_DISCONNECTED);
    assertFalse(group.venueUsable(CLOB2));
    assertTrue(group.venueUsable(CLOB) && group.venueUsable(FUT));
    assertFalse(routerOf(bond).venueUsable(CLOB2));
    assertTrue(routerOf(bond).venueUsable(CLOB));
    // back: reconciling first, usable only once reconciled
    status(AppIds.CLOB2_GATEWAY, ControlMessages.STATUS_RECONCILING);
    assertTrue(group.reconciling());
    assertFalse(group.venueUsable(CLOB2));
    status(AppIds.CLOB2_GATEWAY, ControlMessages.STATUS_RECONCILED);
    assertFalse(group.reconciling());
    assertEquals(1, group.reconciliations());
    assertTrue(group.venueUsable(CLOB2));
  }

  @Test
  void aSilentVenueGoesStaleForTheHedgePlannerAndItsRouter() {
    desk(DeskProfile.v2());
    final int bond = bond(0);
    books(bond);
    assertTrue(group.hedgeAvailable(future()));
    // the CLOBs keep updating, the futures book says nothing for longer than the age limit
    for (int i = 0; i < 12; i++) {
      pass(SECOND);
      level(CLOB, bond, ++feedSeq[CLOB], Side.Buy, BookAction.Change, BOND_BID, (5 + i % 2) * MILLION);
    }
    assertTrue(source.epochNanos() - group.venueBookNanos(FUT) > DeskGroup.MAX_BOOK_AGE_NANOS);
    assertFalse(group.hedgeAvailable(future()), "a silent futures book is not a hedge");
    assertTrue(group.hedgeAvailable(bond));
    final SmartOrderRouter futures = routerOf(future());
    futures.submit(31, future(), Side.Buy, 10_000_000L, 1.0, FUT_BID + 2 * UNIVERSE.future(future()).tick());
    assertTrue(orders(AppIds.FUTURES_GATEWAY).isEmpty(), "nothing is routed off a stale book");
    assertTrue(futures.staleExclusions() > 0);
    // a print is a sign of life: the venue is fresh again
    print(FUT, future(), Side.Buy, FUT_BID + UNIVERSE.future(future()).tick(), 2 * 100L);
    assertTrue(group.hedgeAvailable(future()));
  }

  private void print(final int venue, final int instrument, final Side aggressor, final long price, final long quantity) {
    final TradePrintEncoder e = new TradePrintEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(venue).instrumentId(instrument).venueSeqNo(1).aggressorSide(aggressor)
        .quantity(quantity).timestampNanos(source.epochNanos());
    e.price().mantissa(price);
    run(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }

  @Test
  void aRefusedCancelAndAnOverfillAreHandledOnTheVenueTheyHappenOn() {
    desk(DeskProfile.v2());
    final int bond = bond(2);
    books(bond);
    final SmartOrderRouter futures = routerOf(future());
    final long tick = UNIVERSE.future(future()).tick();
    futures.submit(41, future(), Side.Buy, 2 * 10_000_000L, 1.0, FUT_BID + 4 * tick);
    final long child = orders(AppIds.FUTURES_GATEWAY).get(0)[0];
    report(FUT, future(), child, OrdStatus.New, 0, 0, 0);
    assertTrue(futures.cancel(41));
    final int slot = futures.children().slotOf(child);
    assertEquals(ChildState.CancelRequested, futures.children().state(slot));

    final OrderCancelRejectEncoder e = new OrderCancelRejectEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(FUT).clOrdId(child | AppIds.CANCEL_FLAG).origClOrdId(child)
        .instrumentId(future()).ordStatus(OrdStatus.New).responseTo((short) 1).timestampNanos(source.epochNanos());
    run(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    assertEquals(1, group.cancelRejects());
    assertEquals(ChildState.Acknowledged, futures.children().state(slot), "the refused cancel put the child back");

    // the futures venue fills three contracts on a child of two: booked in face, and counted
    report(FUT, future(), child, OrdStatus.Filled, 3 * 100L, 3 * 100L, FUT_BID + tick);
    assertEquals(1, group.venueOverfills());
    assertEquals(10_000_000L, group.venueOverfillQuantity(), "one contract over, in face");
    assertEquals(3 * 10_000_000L, group.desk().risk().position(future()), "three contracts of face");
    assertEquals(0, group.desk().risk().position(bond));
    assertEquals(3 * 10_000_000L, group.spreadRisk().position(future()), "the spread book follows every fill");
    assertTrue(group.spreadRisk().parallel() < 0, "a long future is short the Treasury spread move");

    // an overfill on the second CLOB is that venue's, in the bond
    final SmartOrderRouter router = routerOf(bond);
    feedSeq[CLOB] += 3;
    level(CLOB, bond, feedSeq[CLOB], Side.Buy, BookAction.Change, BOND_BID, 4 * MILLION);
    router.submit(42, bond, Side.Buy, MILLION, 1.0, BOND_BID + 4 * bondTick(bond));
    final long bondChild = orders(AppIds.CLOB2_GATEWAY).get(0)[0];
    report(CLOB2, bond, bondChild, OrdStatus.Filled, 2 * MILLION, 2 * MILLION, BOND_BID + bondTick(bond));
    assertEquals(2, group.venueOverfills());
    assertEquals(2 * MILLION, group.desk().risk().position(bond));

    // a bust of the futures fill is reversed in face too
    final TradeBustEncoder bust = new TradeBustEncoder();
    bust.wrapAndApplyHeader(buffer, 0, header).venueId(FUT).clOrdId(child).execId(execId - 1).instrumentId(future())
        .side(Side.Buy).quantity(3 * 100L).timestampNanos(source.epochNanos());
    bust.price().mantissa(FUT_BID + tick);
    run(MessageHeaderEncoder.ENCODED_LENGTH + bust.encodedLength());
    assertEquals(1, group.venueBusts());
    assertEquals(0, group.desk().risk().position(future()));
    assertEquals(0, group.spreadRisk().position(future()));
  }

  @Test
  void everyCurveInputKindIsTakenAndAfterARepriceEveryFairValueIsPushed() {
    desk(DeskProfile.v2());
    final int bond = bond(2);
    books(bond);
    final PricingV2 pricing = group.pricingV2();
    assertTrue(pricing.unfedInputs() > 0, "nothing fed yet");
    final long before = pricing.reprices();
    curveRound(3.60);
    assertEquals(0, pricing.unfedInputs(), "fixing, futures and swaps at every tenor were taken");
    assertTrue(pricing.reprices() > before);
    assertTrue(group.repricesPushed() >= 1);
    final long pushed = group.repricesPushed();
    for (final UsdUniverse.SwapSpec s : UNIVERSE.swaps()) {
      assertNotEquals(FairValueSource.NO_VALUE, group.binding().fairMid(s.instrumentId()), s.tenor());
    }
    final long tenYearBefore = group.binding().fairMid(UNIVERSE.swaps().get(5).instrumentId());
    // a second round 5 bp higher moves every swap, and all of them reach the risk engine at once
    pass(500_000_000L);
    curveRound(3.65);
    assertTrue(group.repricesPushed() > pushed);
    assertNotEquals(tenYearBefore, group.binding().fairMid(UNIVERSE.swaps().get(5).instrumentId()));
    // the future's fair value is the mid of its book
    final long tick = UNIVERSE.future(future()).tick();
    assertEquals(FUT_BID + tick / 2, group.binding().fairMid(future()));
  }

  @Test
  void anIncompleteRoundIsAppliedByTheTimerAfterItsConflationTime() {
    desk(DeskProfile.v2());
    final PricingV2 pricing = group.pricingV2();
    curveRound(3.60);
    pass(500_000_000L);
    final long reprices = pricing.reprices();
    final long pushed = group.repricesPushed();
    // one input of the next round, and then nothing more from the feed
    final CurveInputEncoder e = new CurveInputEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).curveId(AppIds.SOFR_CURVE).kind(CurveInputKind.ParSwap)
        .instrumentId(UNIVERSE.swaps().get(5).instrumentId()).tenor("10Y ").timestampNanos(source.epochNanos());
    e.value().mantissa(3_640_000_000L);
    run(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    assertEquals(reprices, pricing.reprices(), "held: the round is not complete");
    assertEquals(1, pricing.pendingInputs());
    // the timer runs every 10 ms and leaves the round alone until it has waited 100 ms
    pass(40_000_000L);
    pass(40_000_000L);
    assertEquals(reprices, pricing.reprices(), "80 ms: still held");
    assertEquals(pushed, group.repricesPushed());
    pass(30_000_000L);
    pass(10_000_000L);
    assertTrue(pricing.reprices() > reprices, "the held input was applied without another curve message");
    assertEquals(0, pricing.pendingInputs());
    assertTrue(group.repricesPushed() > pushed);
  }

  @Test
  void aTradePrintReachesTheBookAndTheRouterOfItsInstrument() {
    desk(DeskProfile.v2());
    final int bond = bond(2);
    books(bond);
    final SmartOrderRouter router = routerOf(bond);
    assertEquals(-1.0, group.book().tradeRate(bond, CLOB2, source.epochNanos()));
    assertNotNull(router.scorecard(), "the ladder router keeps a scorecard");
    print(CLOB2, bond, Side.Sell, BOND_BID, 3 * MILLION);
    assertEquals(1, group.tradePrints());
    assertTrue(group.book().tradeRate(bond, CLOB2, source.epochNanos()) > 0.0);
    assertEquals(-1.0, group.book().tradeRate(bond, CLOB, source.epochNanos()), "the other venue saw no print");
    // a futures print is scaled to face like its book
    print(FUT, future(), Side.Buy, FUT_BID, 4 * 100L);
    assertEquals(2, group.tradePrints());
    final double perSecond = group.book().tradeRate(future(), FUT, source.epochNanos());
    assertTrue(perSecond > 100.0, "contracts counted as face: " + perSecond);
    // a print for a venue or instrument the desk does not have changes nothing
    print(77, bond, Side.Buy, BOND_BID, MILLION);
    print(CLOB, 424_242, Side.Buy, BOND_BID, MILLION);
    assertEquals(2, group.tradePrints());
    // on a gapped venue the print keeps the rate but moves no router
    status(AppIds.CLOB2_FEED_STATUS, ControlMessages.STATUS_DISCONNECTED);
    print(CLOB2, bond, Side.Sell, BOND_BID, MILLION);
    assertEquals(3, group.tradePrints());
  }

  @Test
  void aQuoteIsWithdrawnAtTheVenueOnceHalfItsEdgeIsGone() {
    desk(DeskProfile.v2());
    final int bond = bond(2);
    books(bond);
    curveRound(3.60);
    pass(100_000_000L);
    curveRound(3.60);
    final int swap = UNIVERSE.swaps().get(5).instrumentId();
    final io.ratesalgo.messages.sbe.QuoteRequestEncoder rfq = new io.ratesalgo.messages.sbe.QuoteRequestEncoder();
    rfq.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.D2C_VENUE).rfqId(1).clientId(2001).instrumentId(swap)
        .side(Side.Buy).quantity(10 * MILLION).dealerCount((short) 4).receivedNanos(source.epochNanos()).expiryNanos(0L);
    rfq.putCurrency((byte) 'U', (byte) 'S', (byte) 'D');
    run(MessageHeaderEncoder.ENCODED_LENGTH + rfq.encodedLength());
    assertEquals(1, group.desk().quoting().quotesSent(), "the desk quoted the 10-year swap");
    assertEquals(1, group.desk().quoting().liveQuotes());
    final int[] seen = new int[2];
    out[AppIds.D2C_GATEWAY].read(
        (type, b, index, length) -> seen[type == io.ratesalgo.messages.sbe.QuoteDecoder.TEMPLATE_ID ? 0 : 1]++, 64);
    assertEquals(1, seen[0], "a Quote to the D2C venue");
    assertEquals(0, seen[1]);

    // the curve moves a whole basis point: far more than half of any quoted distance
    pass(200_000_000L);
    curveRound(3.61);
    pass(20_000_000L);
    assertEquals(1, group.desk().quoting().fairMovePulls(), "pulled on the fair-value move");
    assertEquals(0, group.desk().quoting().liveQuotes());
    final int[] withdrawn = new int[1];
    out[AppIds.D2C_GATEWAY].read(
        (type, b, index, length) -> {
          if (type == io.ratesalgo.messages.sbe.QuoteWithdrawDecoder.TEMPLATE_ID) {
            withdrawn[0]++;
          }
        },
        64);
    assertEquals(1, withdrawn[0], "a QuoteWithdraw to the D2C venue, which cancels the quote there");
  }

  @Test
  void theRouteJournalCarriesTheLadderAndScorecardRecords() {
    desk(DeskProfile.v2());
    final int bond = bond(2);
    books(bond);
    routerOf(bond).submit(51, bond, Side.Buy, 2 * MILLION, 1.0, BOND_BID + 4 * bondTick(bond));
    assertTrue(routeTemplates.contains(RouteDecisionModelDecoder.TEMPLATE_ID), "template 84: " + routeTemplates);
    assertTrue(routeTemplates.contains(RouteDecisionLadderDecoder.TEMPLATE_ID), "template 83: " + routeTemplates);
  }

  @Test
  void anOrderTheQuotingSideRestsIsRegisteredWithItsRouterUntilItIsDone() {
    desk(DeskProfile.v2());
    final int bond = bond(2);
    books(bond);
    final SmartOrderRouter router = routerOf(bond);
    final UnsafeBuffer own = new UnsafeBuffer(new byte[128]);
    final NewOrderEncoder e = new NewOrderEncoder();
    e.wrapAndApplyHeader(own, 0, new MessageHeaderEncoder()).venueId(CLOB2).clOrdId(7_001L).parentId(0)
        .instrumentId(bond).side(Side.Sell).ordType(OrdType.Limit).timeInForce(TimeInForce.Day).quantity(3 * MILLION)
        .expireNanos(0).sentNanos(source.epochNanos());
    e.price().mantissa(BOND_BID + bondTick(bond));
    group.publishToVenue(own, 0, MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    assertEquals(1, group.ownOrdersRegistered());
    assertEquals(1, router.registeredOwnOrders());
    assertEquals(1, orders(AppIds.CLOB2_GATEWAY).size(), "and it is still sent to its venue");

    // part of it trades: the router's copy shrinks; then it is done: the copy goes
    ownReport(bond, 7_001L, OrdStatus.PartiallyFilled, MILLION, 2 * MILLION);
    assertEquals(1, router.registeredOwnOrders());
    ownReport(bond, 7_001L, OrdStatus.Filled, 2 * MILLION, 0);
    assertEquals(0, router.registeredOwnOrders());
    // a report for an order that was never registered is not the router's business
    ownReport(bond, 7_002L, OrdStatus.Cancelled, 0, 0);
    assertEquals(0, router.registeredOwnOrders());
  }

  private void ownReport(final int instrument, final long clOrdId, final OrdStatus status, final long last, final long leaves) {
    final ExecutionReportEncoder e = new ExecutionReportEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(CLOB2).clOrdId(clOrdId).venueOrderId(5).execId(++execId)
        .instrumentId(instrument).side(Side.Sell).ordStatus(status).lastQuantity(last).leavesQuantity(leaves)
        .cumQuantity(last).transactNanos(source.epochNanos());
    e.lastPrice().mantissa(last > 0 ? BOND_BID + bondTick(instrument) : 0);
    run(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
  }

  @Test
  void theMarkoutStoreIsSavedAtSessionEndAndLoadedByTheNextDesk() throws Exception {
    final Path start = dir.resolve("markouts.start");
    final Path end = dir.resolve("markouts.end");
    desk(DeskProfile.v2().withMarkouts(start, end));
    assertFalse(Files.exists(end));
    assertTrue(group.endSession());
    assertTrue(Files.exists(end), "the store was written");
    assertFalse(Files.exists(start), "the file a replay reads is never written by the session");

    // the next session starts from what this one ended with, and saves the same store again
    Files.move(end, start);
    final byte[] first = Files.readAllBytes(start);
    desk(DeskProfile.v2().withMarkouts(start, end));
    assertTrue(group.endSession());
    assertEquals(new String(first, java.nio.charset.StandardCharsets.UTF_8),
        Files.readString(end), "the same store gives the same bytes");

    desk(DeskProfile.v2());
    assertFalse(group.endSession(), "no file named, nothing written");
    assertThrows(IllegalArgumentException.class, () -> DeskProfile.v2().withMarkouts(start, start));
  }
}
