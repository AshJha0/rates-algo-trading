package io.ratesalgo.router;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.common.time.TimerHandler;
import io.ratesalgo.common.time.TimerWheel;
import io.ratesalgo.messages.sbe.ChildState;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.RouteReason;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.messages.sbe.VenueExclusion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.agrona.collections.Long2LongHashMap;

/**
 * The smart order router (API_SPEC.md Section 15, docs/ARCHITECTURE.md "Smart order router").
 *
 * <p>A parent order is cut into slices on a randomised schedule whose length and passive time
 * limit shrink with urgency. Each slice is allocated across venues by {@link Allocator}, which
 * prices a passive child (join the touch for at most the passive horizon, then escalate) against
 * an aggressive one (immediate-or-cancel up to a collar past the far touch) on every venue. The
 * whole cost table is journaled before any child is sent. A passive child that reaches its time
 * limit is cancelled and its remainder routed aggressively; a passive child the book has left
 * behind by the threshold is cancelled and re-routed; IOC remainders are retried after a delay;
 * at the deadline everything left goes aggressive. Quantity is re-routed only once the venue has
 * confirmed it will not fill, so the sum over children never exceeds the parent. An aggressive
 * child's limit is capped one tick inside our own resting orders on the other side, so the
 * router never crosses itself.
 *
 * <p>Threading: one thread calls every method. The gateway must not call back into the router
 * from inside {@link OrderGateway#newOrder} or {@link OrderGateway#cancel}; reports are delivered
 * later through {@link ExecutionListener}. After warm-up, {@link #submit}, {@link #poll}, {@link
 * #onExecutionReport}, {@link #onCancelReject} and {@link #onBookUpdate} do not allocate
 * ({@code RouterAllocationTest}).
 */
public final class SmartOrderRouter implements ParentOrderSink, ExecutionListener {

  private static final double SCALE = 1e9;
  private static final long NONE = -1L;
  private static final long SLICE = 1L;
  private static final long RETRY = 2L;
  private static final long DEADLINE = 3L;
  private static final long EXPIRY = 4L;

  private final RouterParams params;
  private final VenueParams[] venues;
  private final BookView book;
  private final OrderGateway gateway;
  private final FrameSink journal;
  private final Clock clock;
  private final ChildRandomiser randomiser;
  private final ChildOrderBook children;
  private final Allocator allocator = new Allocator();
  private final CostTable table = new CostTable();
  private final DecisionCodec codec = new DecisionCodec();
  private final TimerWheel wheel;
  private final Long2LongHashMap timerPayload;
  private final TimerHandler timerHandler = this::onTimer;
  private final long increment;

  private final long[] joinPrice = new long[CostTable.MAX_VENUES];
  private final long[] aggressivePrice = new long[CostTable.MAX_VENUES];

  // parents
  private final int maxParents;
  private final Long2LongHashMap parentSlot;
  private final int[] parentFree;
  private int parentFreeTop;
  private final long[] pId;
  private final int[] pInstrument;
  private final Side[] pSide;
  private final long[] pQty;
  private final long[] pFilled;
  private final double[] pNotional;
  private final long[] pOutstanding;
  private final long[] pRetryQty;
  private final long[] pUrgency;
  private final long[] pLimit;
  private final int[] pSlicesLeft;
  private final int[] pSliceIndex;
  private final long[] pSliceInterval;
  private final long[] pPassiveHorizon;
  private final long[] pArrivalMid;
  private final long[] pSliceTimer;
  private final long[] pRetryTimer;
  private final long[] pDeadlineTimer;
  private final boolean[] pDeadlineMode;

  // per child slot
  private final int[] cParent;
  private final long[] cTimer;
  private final boolean[] cEscalate;

  private boolean polling;
  private long nextClOrdId;
  private long nextDecisionId;
  private long decisions;
  private long childrenSent;
  private long parentsCompleted;

  /**
   * @param params router-wide parameters
   * @param venues the venues to route to, one row each in every decision, at most {@link
   *     CostTable#MAX_VENUES}; every lot size must divide the largest
   * @param book displayed depth per venue
   * @param gateway where child orders go
   * @param journal where RouteDecision, RouteDecisionVenue and ChildOrderTransition records go
   * @param clock time source; {@code nanoTime} for timers, {@code epochNanos} for stamps
   * @param random seeded source for the anti-gaming jitter
   * @param maxParents most parents live at once
   * @param maxChildren most child orders live at once
   * @param clOrdIdBase first child order id; ids increase by one
   */
  public SmartOrderRouter(
      final RouterParams params,
      final VenueParams[] venues,
      final BookView book,
      final OrderGateway gateway,
      final FrameSink journal,
      final Clock clock,
      final RandomSource random,
      final int maxParents,
      final int maxChildren,
      final long clOrdIdBase) {
    if (venues.length < 1 || venues.length > CostTable.MAX_VENUES) {
      throw new IllegalArgumentException(
          "between 1 and " + CostTable.MAX_VENUES + " venues, got " + venues.length);
    }
    long maxLot = 0;
    for (final VenueParams v : venues) {
      maxLot = Math.max(maxLot, v.lotSize());
    }
    for (int i = 0; i < venues.length; i++) {
      if (maxLot % venues[i].lotSize() != 0) {
        throw new IllegalArgumentException(
            "lot size " + venues[i].lotSize() + " does not divide the largest lot " + maxLot);
      }
      for (int j = 0; j < i; j++) {
        if (venues[i].venueId() == venues[j].venueId()) {
          throw new IllegalArgumentException("duplicate venue id " + venues[i].venueId());
        }
      }
    }
    if (maxParents < 1 || maxChildren < 1) {
      throw new IllegalArgumentException("capacities must be >= 1");
    }
    this.params = params;
    this.venues = venues.clone();
    this.book = book;
    this.gateway = gateway;
    this.journal = journal;
    this.clock = clock;
    this.randomiser = new ChildRandomiser(random, params.sizeJitter(), params.intervalJitter());
    this.increment = maxLot;
    this.children = new ChildOrderBook(maxChildren, maxChildren * 64, clock, journal);
    this.wheel = new TimerWheel(1_000_000L, 1_024, 4 * (maxChildren + maxParents), clock.nanoTime());
    this.timerPayload = new Long2LongHashMap(8 * (maxChildren + maxParents), 0.65f, NONE);
    this.maxParents = maxParents;
    this.parentSlot = new Long2LongHashMap(maxParents * 2, 0.65f, NONE);
    this.parentFree = new int[maxParents];
    for (int i = 0; i < maxParents; i++) {
      parentFree[i] = maxParents - 1 - i;
    }
    this.parentFreeTop = maxParents;
    pId = new long[maxParents];
    pInstrument = new int[maxParents];
    pSide = new Side[maxParents];
    pQty = new long[maxParents];
    pFilled = new long[maxParents];
    pNotional = new double[maxParents];
    pOutstanding = new long[maxParents];
    pRetryQty = new long[maxParents];
    pUrgency = new long[maxParents];
    pLimit = new long[maxParents];
    pSlicesLeft = new int[maxParents];
    pSliceIndex = new int[maxParents];
    pSliceInterval = new long[maxParents];
    pPassiveHorizon = new long[maxParents];
    pArrivalMid = new long[maxParents];
    pSliceTimer = new long[maxParents];
    pRetryTimer = new long[maxParents];
    pDeadlineTimer = new long[maxParents];
    pDeadlineMode = new boolean[maxParents];
    cParent = new int[maxChildren];
    cTimer = new long[maxChildren];
    cEscalate = new boolean[maxChildren];
    this.nextClOrdId = clOrdIdBase;
    this.nextDecisionId = 1;
  }

  @Override
  public void submit(
      final long parentId,
      final int instrumentId,
      final Side side,
      final long quantity,
      final double urgency,
      final long limitPrice) {
    if (side != Side.Buy && side != Side.Sell) {
      throw new IllegalArgumentException("side must be Buy or Sell: " + side);
    }
    if (quantity <= 0 || quantity % increment != 0) {
      throw new IllegalArgumentException(
          "quantity must be a positive multiple of " + increment + ": " + quantity);
    }
    if (!(urgency >= 0.0 && urgency <= 1.0)) {
      throw new IllegalArgumentException("urgency must be in [0, 1]: " + urgency);
    }
    if (limitPrice < 0) {
      throw new IllegalArgumentException("limitPrice must be >= 0: " + limitPrice);
    }
    if (parentSlot.get(parentId) != NONE) {
      throw new IllegalArgumentException("parent " + parentId + " is already live");
    }
    if (parentFreeTop == 0) {
      throw new IllegalStateException("parent capacity " + maxParents + " exhausted");
    }
    final int ps = parentFree[--parentFreeTop];
    parentSlot.put(parentId, ps);
    pId[ps] = parentId;
    pInstrument[ps] = instrumentId;
    pSide[ps] = side;
    pQty[ps] = quantity;
    pFilled[ps] = 0;
    pNotional[ps] = 0.0;
    pOutstanding[ps] = 0;
    pRetryQty[ps] = 0;
    pUrgency[ps] = Math.round(urgency * SCALE);
    pLimit[ps] = limitPrice;
    pSliceIndex[ps] = 0;
    pDeadlineMode[ps] = false;
    pSliceTimer[ps] = NONE;
    pRetryTimer[ps] = NONE;
    pArrivalMid[ps] = referenceMid();

    final double patience = 1.0 - urgency;
    final int slices = 1 + (int) Math.round((params.maxSlices() - 1) * patience);
    final long horizon = Math.round(params.scheduleHorizonNanos() * patience);
    long passive = Math.round(params.passiveHorizonMaxNanos() * patience);
    if (passive < params.passiveHorizonMinNanos() || passive <= 0) {
      passive = 0;
    }
    pSlicesLeft[ps] = slices;
    pSliceInterval[ps] = horizon / slices;
    pPassiveHorizon[ps] = passive;
    final long now = clock.nanoTime();
    pDeadlineTimer[ps] =
        schedule(now + horizon + passive + params.retryDelayNanos(), DEADLINE, ps);
    runSlice(ps);
  }

  /** Fires due timers. Call from the owning loop as often as it runs. */
  public int poll() {
    polling = true;
    try {
      return wheel.poll(clock.nanoTime());
    } finally {
      polling = false;
    }
  }

  @Override
  public void onExecutionReport(
      final int venueId,
      final long clOrdId,
      final long execId,
      final OrdStatus ordStatus,
      final long lastPrice,
      final long lastQuantity,
      final long leavesQuantity,
      final long cumQuantity,
      final long transactNanos) {
    children.onExecutionReport(
        venueId, clOrdId, execId, ordStatus, lastPrice, lastQuantity, cumQuantity);
    final int cs = children.lastSlot();
    if (cs >= 0) {
      afterChildChange(cs);
    }
  }

  @Override
  public void onCancelReject(final int venueId, final long clOrdId) {
    final int cs = children.slotOf(clOrdId);
    if (cs >= 0 && children.onCancelReject(venueId, clOrdId)) {
      cEscalate[cs] = false;
    }
  }

  @Override
  public void onBookUpdate(final int venueId) {
    final int r = rowOf(venueId);
    if (r < 0) {
      return;
    }
    final long bid = book.bestBid(venueId);
    final long ask = book.bestAsk(venueId);
    final long threshold = params.bookMoveThresholdTicks() * venues[r].tickMantissa();
    final int hw = children.highWater();
    for (int cs = 0; cs < hw; cs++) {
      if (!children.inUse(cs)
          || !children.passive(cs)
          || children.venueId(cs) != venueId
          || !ChildOrderBook.isLive(children.state(cs))) {
        continue;
      }
      final long px = children.price(cs);
      final boolean behind =
          children.side(cs) == Side.Buy
              ? bid > 0 && bid - px >= threshold
              : ask > 0 && px - ask >= threshold;
      if (behind) {
        cancelChild(cs, false);
      }
    }
  }

  /**
   * Mass status reconciliation for one venue after a reconnect. Children the venue does not know
   * are treated as cancelled; children the venue reports finished are closed; working orders the
   * venue reports that we do not hold as live are cancelled. Released quantity is re-routed.
   * Off the hot path: allocates.
   */
  public ReconcileReport reconcile(final int venueId, final List<VenueOrderStatus> statuses) {
    final Map<Long, VenueOrderStatus> byId = new HashMap<>();
    for (final VenueOrderStatus s : statuses) {
      byId.put(s.clOrdId(), s);
    }
    int checked = 0;
    int missing = 0;
    int closed = 0;
    long released = 0;
    final int hw = children.highWater();
    for (int cs = 0; cs < hw; cs++) {
      if (!children.inUse(cs)
          || children.venueId(cs) != venueId
          || ChildOrderBook.isTerminal(children.state(cs))) {
        continue;
      }
      checked++;
      final VenueOrderStatus s = byId.remove(children.clOrdId(cs));
      final long freed;
      if (s == null) {
        missing++;
        freed = children.reconcile(children.clOrdId(cs), false, OrdStatus.Cancelled, 0);
      } else {
        freed = children.reconcile(children.clOrdId(cs), true, s.status(), s.cumQuantity());
        if (ChildOrderBook.isTerminal(children.state(cs))) {
          closed++;
        }
      }
      released += freed;
      if (freed > 0) {
        final int ps = cParent[cs];
        pOutstanding[ps] -= freed;
        cancelTimer(cTimer[cs]);
        cTimer[cs] = NONE;
        final long done = decide(ps, freed, RouteReason.Reconcile, passiveAllowed(ps));
        addRetry(ps, freed - done);
        releaseIfDone(cs);
      }
    }
    int orphans = 0;
    for (final VenueOrderStatus s : byId.values()) {
      if (s.status() == OrdStatus.New || s.status() == OrdStatus.PartiallyFilled) {
        gateway.cancel(venueId, s.clOrdId());
        orphans++;
      }
    }
    return new ReconcileReport(checked, missing, closed, orphans, released);
  }

  // ------------------------------------------------------------------ internals

  private void afterChildChange(final int cs) {
    final int ps = cParent[cs];
    final long fill = children.lastFill();
    if (fill > 0) {
      pFilled[ps] += fill;
      pNotional[ps] += (double) children.lastFillPrice() * fill;
      // an overfill was already taken out of outstanding when the cancel freed it
      pOutstanding[ps] -= fill - children.lastOverfill();
    }
    final long freed = children.lastFreed();
    if (freed > 0) {
      pOutstanding[ps] -= freed;
      cancelTimer(cTimer[cs]);
      cTimer[cs] = NONE;
      onFreed(cs, ps, freed);
    }
    releaseIfDone(cs);
    // after an overfill the parent can be filled past its quantity; it finishes once nothing is
    // left outstanding on its children, so no live child keeps a freed parent slot
    if (pFilled[ps] >= pQty[ps] && pOutstanding[ps] == 0) {
      finishParent(ps);
    }
  }

  private void onFreed(final int cs, final int ps, final long freed) {
    final long routed;
    if (pDeadlineMode[ps]) {
      routed = decide(ps, freed, RouteReason.Deadline, false);
    } else if (cEscalate[cs]) {
      routed = decide(ps, freed, RouteReason.Escalation, false);
    } else if (children.replaceIntent(cs)) {
      routed = decide(ps, freed, RouteReason.BookMove, passiveAllowed(ps));
    } else {
      routed = 0;
    }
    addRetry(ps, freed - routed);
  }

  private void releaseIfDone(final int cs) {
    if (ChildOrderBook.isTerminal(children.state(cs)) && children.leaves(cs) == 0) {
      cancelTimer(cTimer[cs]);
      cTimer[cs] = NONE;
      children.release(cs);
    }
  }

  private void finishParent(final int ps) {
    cancelTimer(pSliceTimer[ps]);
    cancelTimer(pRetryTimer[ps]);
    cancelTimer(pDeadlineTimer[ps]);
    pSliceTimer[ps] = NONE;
    pRetryTimer[ps] = NONE;
    pDeadlineTimer[ps] = NONE;
    parentSlot.remove(pId[ps]);
    parentFree[parentFreeTop++] = ps;
    parentsCompleted++;
  }

  private void runSlice(final int ps) {
    pSliceTimer[ps] = NONE;
    final long schedulable = unrouted(ps) - pRetryQty[ps];
    if (schedulable <= 0 || pSlicesLeft[ps] <= 0) {
      return;
    }
    final long size = randomiser.sliceSize(schedulable, pSlicesLeft[ps], increment);
    pSlicesLeft[ps]--;
    final long routed = decide(ps, size, RouteReason.Slice, passiveAllowed(ps));
    pSliceIndex[ps]++;
    addRetry(ps, size - routed);
    if (pSlicesLeft[ps] > 0 && unrouted(ps) - pRetryQty[ps] > 0) {
      pSliceTimer[ps] =
          schedule(clock.nanoTime() + randomiser.interval(pSliceInterval[ps]), SLICE, ps);
    }
  }

  private boolean passiveAllowed(final int ps) {
    return !pDeadlineMode[ps] && pPassiveHorizon[ps] > 0;
  }

  private void addRetry(final int ps, final long quantity) {
    if (quantity <= 0) {
      return;
    }
    pRetryQty[ps] += quantity;
    if (pRetryTimer[ps] == NONE) {
      pRetryTimer[ps] = schedule(clock.nanoTime() + params.retryDelayNanos(), RETRY, ps);
    }
  }

  private void onRetry(final int ps) {
    pRetryTimer[ps] = NONE;
    final long want = Math.min(pRetryQty[ps], unrouted(ps));
    pRetryQty[ps] = 0;
    final long routed =
        decide(
            ps,
            want,
            pDeadlineMode[ps] ? RouteReason.Deadline : RouteReason.Retry,
            passiveAllowed(ps));
    addRetry(ps, want - routed);
  }

  private void onDeadline(final int ps) {
    pDeadlineTimer[ps] = NONE;
    pDeadlineMode[ps] = true;
    cancelTimer(pSliceTimer[ps]);
    pSliceTimer[ps] = NONE;
    pSlicesLeft[ps] = 0;
    final int hw = children.highWater();
    for (int cs = 0; cs < hw; cs++) {
      if (children.inUse(cs)
          && cParent[cs] == ps
          && children.parentId(cs) == pId[ps]
          && children.passive(cs)
          && ChildOrderBook.isLive(children.state(cs))) {
        cancelChild(cs, true);
      }
    }
    final long want = unrouted(ps);
    pRetryQty[ps] = 0;
    cancelTimer(pRetryTimer[ps]);
    pRetryTimer[ps] = NONE;
    final long routed = decide(ps, want, RouteReason.Deadline, false);
    addRetry(ps, want - routed);
  }

  private void cancelChild(final int cs, final boolean escalate) {
    if (children.requestCancel(children.clOrdId(cs), true)) {
      cEscalate[cs] = escalate;
      cancelTimer(cTimer[cs]);
      cTimer[cs] = NONE;
      gateway.cancel(children.venueId(cs), children.clOrdId(cs));
    }
  }

  private long unrouted(final int ps) {
    return pQty[ps] - pFilled[ps] - pOutstanding[ps];
  }

  /** Builds, allocates, journals and sends one decision; returns the quantity sent. */
  private long decide(final int ps, final long wanted, final RouteReason reason, final boolean passiveOk) {
    long quantity = Math.min(wanted, unrouted(ps));
    quantity -= quantity % increment;
    if (quantity <= 0) {
      return 0;
    }
    final CostTable t = table;
    final Side side = pSide[ps];
    t.decisionId = nextDecisionId++;
    t.parentId = pId[ps];
    t.instrumentId = pInstrument[ps];
    t.side = side;
    t.reason = reason;
    t.configVersion = params.configVersion();
    t.venueCount = venues.length;
    t.sliceIndex = pSliceIndex[ps];
    t.parentQuantity = pQty[ps];
    t.sliceQuantity = quantity;
    t.urgency = pUrgency[ps];
    t.passiveHorizonNanos = passiveOk ? pPassiveHorizon[ps] : 0;
    t.sliceIntervalNanos = Math.max(pSliceInterval[ps], params.retryDelayNanos());
    t.arrivalMid = pArrivalMid[ps];
    t.timestampNanos = clock.epochNanos();
    for (int r = 0; r < venues.length; r++) {
      fillRow(t, r, ps, side);
    }
    allocator.allocate(t, params, venues);
    codec.write(t, journal);
    decisions++;
    for (int r = 0; r < venues.length; r++) {
      if (t.allocated[r] > 0) {
        sendChild(ps, r, t.allocated[r], t.passive[r], t.passiveHorizonNanos);
      }
    }
    return t.allocatedQuantity;
  }

  private void fillRow(final CostTable t, final int r, final int ps, final Side side) {
    final VenueParams vp = venues[r];
    final int v = vp.venueId();
    final long bid = book.bestBid(v);
    final long ask = book.bestAsk(v);
    final boolean buy = side == Side.Buy;
    final Side opposite = buy ? Side.Sell : Side.Buy;
    t.venueId[r] = v;
    t.kind[r] = vp.kind();
    t.bestBid[r] = bid;
    t.bestAsk[r] = ask;
    t.tick[r] = vp.tickMantissa();
    t.lotSize[r] = vp.lotSize();
    t.minSize[r] = vp.minSize();
    t.takerFee[r] = Math.round(vp.takerFee() * SCALE);
    t.makerFee[r] = Math.round(vp.makerFee() * SCALE);
    t.latencyNanos[r] = vp.latencyNanos();
    t.sameSideDepth[r] = Math.max(0, book.depth(v, side, 0));
    long opp = 0;
    for (int k = 0; k < vp.depthLevels(); k++) {
      opp += Math.max(0, book.depth(v, opposite, k));
    }
    t.oppositeDepth[r] = opp;
    final double rate = book.tradeRatePerSecond(v);
    t.tradeRate[r] = rate > 0.0 && Double.isFinite(rate) ? Math.round(rate * SCALE) : 0;
    t.exclusion[r] = VenueExclusion.None;
    t.passiveAllowed[r] = false;
    t.aggressiveAllowed[r] = false;
    t.participationCap[r] = 0;
    if (bid <= 0 || ask <= bid) {
      t.exclusion[r] = VenueExclusion.NoQuote;
      return;
    }
    final long tick = vp.tickMantissa();
    final long join = buy ? bid : ask;
    final long far = buy ? ask : bid;
    long limit = buy ? far + params.aggressiveCollarTicks() * tick : far - params.aggressiveCollarTicks() * tick;
    final long own = ownBestOpposite(v, opposite);
    boolean selfBlocked = false;
    if (own > 0) {
      final long cap = buy ? own - tick : own + tick;
      if (buy ? cap < limit : cap > limit) {
        limit = cap;
      }
      selfBlocked = buy ? join >= own : join <= own;
    }
    final long parentLimit = pLimit[ps];
    boolean limitBlocked = false;
    if (parentLimit > 0) {
      if (buy ? parentLimit < limit : parentLimit > limit) {
        limit = parentLimit;
      }
      limitBlocked = buy ? join > parentLimit : join < parentLimit;
    }
    t.aggressiveAllowed[r] = buy ? limit >= far : limit <= far;
    t.passiveAllowed[r] = !selfBlocked && !limitBlocked && t.passiveHorizonNanos > 0;
    joinPrice[r] = join;
    aggressivePrice[r] = limit;
    if (!t.aggressiveAllowed[r] && !t.passiveAllowed[r]) {
      t.exclusion[r] = own > 0 ? VenueExclusion.SelfCross : VenueExclusion.LimitPrice;
      return;
    }
    final double liquidity = opp + rate * (t.sliceIntervalNanos / SCALE);
    final long capQty = (long) Math.floor(vp.participationCap() * liquidity);
    t.participationCap[r] = capQty - capQty % vp.lotSize();
  }

  /**
   * Our most aggressive live price on {@code side} at a venue (lowest sell or highest buy), over
   * resting and in-flight children alike, since an IOC in flight and a new resting order can reach
   * the venue in either order; 0 if none.
   */
  private long ownBestOpposite(final int venueId, final Side side) {
    long best = 0;
    final int hw = children.highWater();
    for (int cs = 0; cs < hw; cs++) {
      if (!children.inUse(cs)
          || children.venueId(cs) != venueId
          || children.side(cs) != side) {
        continue;
      }
      final ChildState st = children.state(cs);
      if (!ChildOrderBook.isLive(st) && st != ChildState.CancelRequested) {
        continue;
      }
      final long px = children.price(cs);
      if (best == 0 || (side == Side.Sell ? px < best : px > best)) {
        best = px;
      }
    }
    return best;
  }

  private void sendChild(
      final int ps, final int r, final long qty, final boolean passive, final long horizon) {
    final VenueParams vp = venues[r];
    final long id = nextClOrdId++;
    final long price = passive ? joinPrice[r] : aggressivePrice[r];
    final int cs = children.open(id, pId[ps], vp.venueId(), pSide[ps], price, qty, passive);
    cParent[cs] = ps;
    cEscalate[cs] = false;
    cTimer[cs] = NONE;
    pOutstanding[ps] += qty;
    childrenSent++;
    gateway.newOrder(
        vp.venueId(),
        id,
        pId[ps],
        pInstrument[ps],
        pSide[ps],
        OrdType.Limit,
        passive ? TimeInForce.Day : TimeInForce.ImmediateOrCancel,
        price,
        qty);
    if (passive) {
      cTimer[cs] = schedule(clock.nanoTime() + horizon, EXPIRY, cs);
    }
  }

  private long referenceMid() {
    for (final VenueParams vp : venues) {
      final long bid = book.bestBid(vp.venueId());
      final long ask = book.bestAsk(vp.venueId());
      if (bid > 0 && ask > bid) {
        return (bid + ask) / 2;
      }
    }
    return 0;
  }

  private int rowOf(final int venueId) {
    for (int r = 0; r < venues.length; r++) {
      if (venues[r].venueId() == venueId) {
        return r;
      }
    }
    return -1;
  }

  private long schedule(final long deadline, final long kind, final int slot) {
    final long id = wheel.schedule(deadline, timerHandler);
    timerPayload.put(id, (kind << 32) | slot);
    return id;
  }

  private void cancelTimer(final long id) {
    // Inside poll() a timer due in the same poll may already be queued for firing, and
    // TimerWheel.cancel does not handle that case; dropping the payload makes the firing a no-op.
    if (id != NONE && timerPayload.remove(id) != NONE && !polling) {
      wheel.cancel(id);
    }
  }

  private void onTimer(final long timerId, final long nowNanos) {
    final long payload = timerPayload.remove(timerId);
    if (payload == NONE) {
      return;
    }
    final long kind = payload >>> 32;
    final int slot = (int) payload;
    if (kind == EXPIRY) {
      cTimer[slot] = NONE;
      if (children.inUse(slot) && ChildOrderBook.isLive(children.state(slot))) {
        cancelChild(slot, true);
      }
    } else if (kind == SLICE) {
      runSlice(slot);
    } else if (kind == RETRY) {
      onRetry(slot);
    } else if (kind == DEADLINE) {
      onDeadline(slot);
    }
  }

  // ------------------------------------------------------------------ queries

  /** True while the parent has unfilled quantity. */
  public boolean isActive(final long parentId) {
    return parentSlot.get(parentId) != NONE;
  }

  /** Filled quantity of a live parent, or -1 if the parent is not live. */
  public long filled(final long parentId) {
    final int ps = (int) parentSlot.get(parentId);
    return ps < 0 ? -1 : pFilled[ps];
  }

  /**
   * The parent's quantity accounting: {@code {quantity, filled, outstanding, unrouted}}; null if
   * the parent is not live. Allocates; for tests.
   */
  public long[] parentAccounting(final long parentId) {
    final int ps = (int) parentSlot.get(parentId);
    return ps < 0 ? null : new long[] {pQty[ps], pFilled[ps], pOutstanding[ps], unrouted(ps)};
  }

  /** The child order book, for inspection. */
  public ChildOrderBook children() {
    return children;
  }

  /** The table of the most recent decision. */
  public CostTable lastDecision() {
    return table;
  }

  /** Decisions taken. */
  public long decisions() {
    return decisions;
  }

  /** Child orders sent. */
  public long childrenSent() {
    return childrenSent;
  }

  /** Parents completely filled. */
  public long parentsCompleted() {
    return parentsCompleted;
  }

  /** The parameters in force. */
  public RouterParams params() {
    return params;
  }

  /** Venue parameters in row order (a copy). */
  public VenueParams[] venues() {
    return venues.clone();
  }
}
