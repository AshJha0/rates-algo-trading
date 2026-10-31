package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.ChildState;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.RouteReason;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.messages.sbe.VenueExclusion;
import io.ratesalgo.router.sim.SimConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Router behaviour: passive first, escalation, re-route on book moves, self-cross, deadline. */
class SmartOrderRouterTest {

  private static final long MM = RouterPresets.ONE_MILLION;
  private static final long TICK = RouterPresets.TICK_64TH;
  private static final long BID = 98_500_000_000L;

  private final ManualClock clock = new ManualClock();
  private final StubVenues venues = new StubVenues(1, 2);
  private final List<RouteReason> reasons = new ArrayList<>();
  private final List<CostTable> tables = new ArrayList<>();
  private final DecisionCodec codec = new DecisionCodec();
  private long execId = 1;

  /** Params with one slice, no schedule, and the given passive horizon. */
  static RouterParams oneSlice(final long passiveNanos) {
    final RouterParams g = RouterPresets.global();
    return new RouterParams(
        1, g.sigmaDailyPoints(), g.tradingSecondsPerDay(), g.impactY(), g.impactAlpha(),
        g.adverseFactor(), g.latencyRiskFactor(), passiveNanos, 0L, 1, 0L, g.sizeJitter(),
        g.intervalJitter(), 2, 8, 500_000_000L, 32);
  }

  private SmartOrderRouter router(final RouterParams p) {
    return new SmartOrderRouter(
        p,
        new VenueParams[] {RouterPresets.clob(1), RouterPresets.clob(2)},
        venues,
        venues,
        (templateId, version, buffer, offset, length) -> {
          if (codec.onFrame(templateId, buffer, offset, length)) {
            final CostTable copy = new CostTable();
            copy.copyFrom(codec.decoded());
            tables.add(copy);
            reasons.add(copy.reason());
          }
        },
        clock,
        RandomSource.seeded(1L),
        4,
        64,
        100L);
  }

  @BeforeEach
  void quotes() {
    for (int i = 0; i < 2; i++) {
      venues.bid[i] = BID;
      venues.ask[i] = BID + TICK;
      venues.bidDepth[i] = 10 * MM;
      venues.askDepth[i] = 10 * MM;
      venues.rate[i] = 2.0 * MM;
    }
  }

  private void report(
      final SmartOrderRouter r, final StubVenues.Order o, final OrdStatus st, final long last,
      final long cum) {
    r.onExecutionReport(o.venue(), o.id(), execId++, st, o.price(), last, o.qty() - cum, cum, 0L);
  }

  /** Makes venue 2 the obvious passive venue: empty queue and heavy flow. */
  private void passiveFriendlyVenue2() {
    venues.bidDepth[1] = 0;
    venues.askDepth[1] = 0;
    venues.rate[1] = 200.0 * MM;
    venues.bidDepth[0] = 200 * MM;
    venues.rate[0] = 0.1 * MM;
  }

  @Test
  void passiveChildEscalatesAtItsTimeLimit() {
    passiveFriendlyVenue2();
    final SmartOrderRouter r = router(oneSlice(5_000_000_000L));
    r.submit(1, 10, Side.Buy, 2 * MM, 0.0, 0L);
    final StubVenues.Order passive = venues.last();
    assertEquals(TimeInForce.Day, passive.tif(), tables.get(0).format());
    assertEquals(2, passive.venue());
    assertEquals(BID, passive.price());
    report(r, passive, OrdStatus.New, 0, 0);
    report(r, passive, OrdStatus.PartiallyFilled, MM, MM);
    clock.advance(5_001_000_000L);
    r.poll();
    assertEquals(1, venues.cancels.size());
    report(r, passive, OrdStatus.Cancelled, 0, MM);
    assertEquals(RouteReason.Escalation, reasons.get(reasons.size() - 1));
    final StubVenues.Order aggressive = venues.last();
    assertEquals(TimeInForce.ImmediateOrCancel, aggressive.tif());
    assertEquals(MM, aggressive.qty());
    assertTrue(aggressive.price() >= BID + TICK);
    report(r, aggressive, OrdStatus.Filled, MM, MM);
    assertFalse(r.isActive(1));
    assertEquals(1, r.parentsCompleted());
    assertEquals(0, r.children().anomalies());
  }

  /**
   * DR-R2R-03: the venue confirms a cancel with a cumulative quantity of 1 MM on a 2 MM child,
   * the router re-routes the freed 1 MM, and then a fill for the whole 2 MM arrives on the
   * cancelled child. The extra 1 MM was no longer outstanding: the parent stays open until the
   * re-routed child resolves, finishes then, and the overfill is counted.
   */
  @Test
  void aFillBeyondTheConfirmedCancelKeepsParentAccountingWhole() {
    passiveFriendlyVenue2();
    final SmartOrderRouter r = router(oneSlice(5_000_000_000L));
    r.submit(7, 10, Side.Buy, 2 * MM, 0.0, 0L);
    final StubVenues.Order passive = venues.last();
    report(r, passive, OrdStatus.New, 0, 0);
    clock.advance(5_001_000_000L);
    r.poll();
    report(r, passive, OrdStatus.Cancelled, 0, MM);
    final StubVenues.Order aggressive = venues.last();
    assertEquals(MM, aggressive.qty());
    report(r, passive, OrdStatus.Filled, 2 * MM, 2 * MM);
    assertEquals(MM, r.children().lastOverfill());
    assertTrue(r.isActive(7), "the re-routed child is still working");
    assertEquals(0, r.parentsCompleted());
    report(r, aggressive, OrdStatus.Filled, MM, MM);
    assertFalse(r.isActive(7));
    assertEquals(1, r.parentsCompleted());
    assertEquals(MM, r.children().overfills());
  }

  @Test
  void bookMovingAwayCancelsAndReroutes() {
    passiveFriendlyVenue2();
    final SmartOrderRouter r = router(oneSlice(30_000_000_000L));
    r.submit(2, 10, Side.Buy, 3 * MM, 0.0, 0L);
    final StubVenues.Order passive = venues.last();
    assertEquals(TimeInForce.Day, passive.tif());
    report(r, passive, OrdStatus.New, 0, 0);
    venues.bid[1] = BID + TICK;
    venues.ask[1] = BID + 2 * TICK;
    r.onBookUpdate(2);
    assertTrue(venues.cancels.isEmpty(), "one tick is below the threshold");
    venues.bid[1] = BID + 2 * TICK;
    venues.ask[1] = BID + 3 * TICK;
    r.onBookUpdate(2);
    r.onBookUpdate(99);
    assertEquals(1, venues.cancels.size());
    report(r, passive, OrdStatus.Cancelled, 0, 0);
    assertEquals(RouteReason.BookMove, reasons.get(reasons.size() - 1));
    final StubVenues.Order again = venues.last();
    assertEquals(BID + 2 * TICK, again.price());
    final int slot = r.children().slotOf(passive.id());
    assertEquals(-1, slot, "the replaced child is released");
    // venue 2's book is now two ticks above venue 1's, so the re-route no longer sends the whole
    // 3 MM back to venue 2: the cheaper venue takes part of it (DR-R2R-04)
    final CostTable reroute = tables.get(tables.size() - 1);
    assertEquals(3 * MM, reroute.allocatedQuantity, reroute.format());
    assertTrue(reroute.allocated[0] > 0, reroute.format());
    assertTrue(again.qty() < 3 * MM);
  }

  @Test
  void neverCrossesOurOwnRestingOrders() {
    passiveFriendlyVenue2();
    venues.rate[0] = 200.0 * MM;
    venues.askDepth[0] = 0;
    venues.askDepth[1] = 0;
    final SmartOrderRouter r = router(oneSlice(30_000_000_000L));
    r.submit(3, 10, Side.Sell, 2 * MM, 0.0, 0L);
    final List<StubVenues.Order> sells = new ArrayList<>(venues.orders);
    assertTrue(sells.stream().allMatch(o -> o.tif() == TimeInForce.Day), tables.get(0).format());
    final int ownVenue = sells.get(0).venue();
    // an urgent buy must not lift our own offer on that venue
    r.submit(4, 10, Side.Buy, 2 * MM, 1.0, 0L);
    final CostTable buy = tables.get(tables.size() - 1);
    for (int row = 0; row < buy.venueCount(); row++) {
      if (buy.venueId(row) == ownVenue) {
        assertEquals(VenueExclusion.SelfCross, buy.exclusion(row), buy.format());
        assertEquals(0, buy.allocated(row));
      }
    }
    for (final StubVenues.Order o : venues.orders) {
      if (o.parent() == 4) {
        assertFalse(o.venue() == ownVenue, "crossed own order on venue " + o.venue());
      }
    }
  }

  @Test
  void iocRemainderIsRetriedAfterTheDelay() {
    final SmartOrderRouter r = router(oneSlice(0L));
    r.submit(5, 10, Side.Buy, 4 * MM, 1.0, 0L);
    final List<StubVenues.Order> first = new ArrayList<>(venues.orders);
    long sent = 0;
    for (final StubVenues.Order o : first) {
      assertEquals(TimeInForce.ImmediateOrCancel, o.tif());
      sent += o.qty();
    }
    assertEquals(4 * MM, sent);
    final StubVenues.Order o = first.get(0);
    report(r, o, OrdStatus.PartiallyFilled, MM, MM);
    report(r, o, OrdStatus.Cancelled, 0, MM);
    final int before = venues.orders.size();
    clock.advance(400_000_000L);
    r.poll();
    assertEquals(before, venues.orders.size());
    clock.advance(200_000_000L);
    r.poll();
    assertEquals(RouteReason.Deadline, reasons.get(reasons.size() - 1));
    assertTrue(venues.orders.size() > before);
    final long[] acct = r.parentAccounting(5);
    assertEquals(4 * MM, acct[0]);
    assertEquals(MM, acct[1]);
    assertEquals(acct[0], acct[1] + acct[2] + acct[3]);
    assertNull(r.parentAccounting(77));
    assertEquals(-1, r.filled(77));
    assertEquals(MM, r.filled(5));
  }

  @Test
  void retryReroutesBeforeTheDeadline() {
    final SmartOrderRouter r = router(oneSlice(0L).withHorizons(60_000_000_000L, 0L));
    r.submit(15, 10, Side.Buy, 2 * MM, 0.0, 0L);
    final StubVenues.Order o = venues.orders.get(0);
    report(r, o, OrdStatus.Cancelled, 0, 0);
    clock.advance(600_000_000L);
    r.poll();
    assertEquals(RouteReason.Retry, reasons.get(reasons.size() - 1));
  }

  @Test
  void deadlineEscalatesRestingChildren() {
    passiveFriendlyVenue2();
    final RouterParams p =
        new RouterParams(
            1, 0.5, 23_400.0, 0.6, 0.5, 0.5, 1.0, 10_000_000_000L, 0L, 2, 4_000_000_000L,
            0.0, 0.0, 2, 8, 500_000_000L, 32);
    final SmartOrderRouter r = router(p);
    r.submit(6, 10, Side.Buy, 4 * MM, 0.0, 0L);
    final StubVenues.Order firstSlice = venues.last();
    report(r, firstSlice, OrdStatus.New, 0, 0);
    clock.advance(2_000_000_000L);
    r.poll();
    final StubVenues.Order secondSlice = venues.last();
    assertFalse(firstSlice.id() == secondSlice.id());
    report(r, secondSlice, OrdStatus.New, 0, 0);
    clock.advance(12_500_000_000L);
    r.poll();
    assertTrue(venues.cancels.size() >= 2);
    report(r, firstSlice, OrdStatus.Cancelled, 0, 0);
    assertEquals(RouteReason.Deadline, reasons.get(reasons.size() - 1));
    assertEquals(TimeInForce.ImmediateOrCancel, venues.last().tif());
  }

  @Test
  void limitPriceAndMissingQuotesExcludeVenues() {
    venues.bid[0] = 0;
    final SmartOrderRouter r = router(oneSlice(0L));
    r.submit(7, 10, Side.Buy, 2 * MM, 1.0, BID);
    final CostTable t = tables.get(0);
    assertEquals(VenueExclusion.NoQuote, t.exclusion(0));
    assertEquals(VenueExclusion.LimitPrice, t.exclusion(1));
    assertEquals(0, t.allocatedQuantity());
    assertTrue(venues.orders.isEmpty());
  }

  @Test
  void cancelRejectRestoresTheChild() {
    passiveFriendlyVenue2();
    final SmartOrderRouter r = router(oneSlice(5_000_000_000L));
    r.submit(8, 10, Side.Buy, 2 * MM, 0.0, 0L);
    final StubVenues.Order passive = venues.last();
    report(r, passive, OrdStatus.New, 0, 0);
    clock.advance(5_001_000_000L);
    r.poll();
    r.onCancelReject(passive.venue(), passive.id());
    r.onCancelReject(passive.venue(), 999_999L);
    assertEquals(
        ChildState.Acknowledged, r.children().state(r.children().slotOf(passive.id())));
  }

  @Test
  void reconcileClosesMissingAndCancelsOrphans() {
    passiveFriendlyVenue2();
    final SmartOrderRouter r = router(oneSlice(30_000_000_000L));
    r.submit(9, 10, Side.Buy, 2 * MM, 0.0, 0L);
    final StubVenues.Order passive = venues.last();
    final int before = venues.orders.size();
    final ReconcileReport missing = r.reconcile(passive.venue(), List.of(
        new VenueOrderStatus(424_242L, OrdStatus.New, 0)));
    assertEquals(1, missing.checked());
    assertEquals(1, missing.missingAtVenue());
    assertEquals(1, missing.orphansCancelled());
    assertEquals(2 * MM, missing.releasedQuantity());
    assertTrue(venues.orders.size() > before);
    assertEquals(RouteReason.Reconcile, reasons.get(reasons.size() - 1));

    final StubVenues.Order next = venues.last();
    final ReconcileReport closed = r.reconcile(next.venue(), List.of(
        new VenueOrderStatus(next.id(), OrdStatus.Cancelled, MM)));
    assertEquals(1, closed.closed());
    assertEquals(MM, closed.releasedQuantity());
    assertThrows(
        IllegalArgumentException.class, () -> new VenueOrderStatus(1, OrdStatus.New, -1));
  }

  @Test
  void inputsAreValidated() {
    final SmartOrderRouter r = router(oneSlice(0L));
    assertThrows(IllegalArgumentException.class, () -> r.submit(1, 1, Side.NULL_VAL, MM, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> r.submit(1, 1, Side.Buy, MM / 2, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> r.submit(1, 1, Side.Buy, MM, 1.5, 0));
    assertThrows(IllegalArgumentException.class, () -> r.submit(1, 1, Side.Buy, MM, 0.5, -1));
    venues.bid[0] = 0;
    venues.bid[1] = 0;
    r.submit(1, 1, Side.Buy, MM, 0.5, 0);
    assertThrows(IllegalArgumentException.class, () -> r.submit(1, 1, Side.Buy, MM, 0.5, 0));
    r.submit(2, 1, Side.Buy, MM, 0.5, 0);
    r.submit(3, 1, Side.Buy, MM, 0.5, 0);
    r.submit(4, 1, Side.Buy, MM, 0.5, 0);
    assertThrows(IllegalStateException.class, () -> r.submit(5, 1, Side.Buy, MM, 0.5, 0));
    final VenueParams a = RouterPresets.clob(1);
    final VenueParams f = RouterPresets.futures(2);
    final RouterParams p = RouterPresets.global();
    assertThrows(IllegalArgumentException.class, () -> new SmartOrderRouter(
        p, new VenueParams[] {a, a}, venues, venues, FrameSink.NONE, clock, () -> 0.5, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new SmartOrderRouter(
        p, new VenueParams[0], venues, venues, FrameSink.NONE, clock, () -> 0.5, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new SmartOrderRouter(
        p, new VenueParams[] {a}, venues, venues, FrameSink.NONE, clock, () -> 0.5, 0, 1, 1));
    final SmartOrderRouter mixed = new SmartOrderRouter(
        p, new VenueParams[] {a, f}, venues, venues, FrameSink.NONE, clock, () -> 0.5, 1, 1, 1);
    assertEquals(2, mixed.venues().length);
    assertEquals(p, mixed.params());
    assertEquals(0, mixed.decisions());
    assertEquals(0, mixed.childrenSent());
    assertEquals(0, mixed.lastDecision().venueCount());
  }

  @Test
  void completesParentsOnTheSimulatedVenues() {
    final Rig rig = new Rig(SimConfig.ust10yThreeVenues(), RouterPresets.global(), 11L);
    long id = 100;
    for (final double urgency : new double[] {0.0, 0.5, 1.0}) {
      for (final Side side : new Side[] {Side.Buy, Side.Sell}) {
        id++;
        rig.router.submit(id, 10, side, 25 * MM, urgency, 0L);
        assertTrue(rig.runUntilDone(id, 20_000) >= 0, "parent " + id + " did not complete");
        assertEquals(25 * MM, rig.market.parentFilled(id));
        rig.step(50);
      }
    }
    assertEquals(0, rig.market.selfCrosses());
    assertEquals(0, rig.router.children().anomalies());
    assertTrue(rig.router.decisions() >= 6);
    assertTrue(rig.router.childrenSent() >= 6);
    assertTrue(rig.frames.stream().anyMatch(f -> f.templateId() == 82));
    assertEquals(0, rig.market.liveOrders());
  }
}
