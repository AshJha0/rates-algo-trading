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
 * <p>Nothing waits for ever ({@link RouterTimeouts}). A child the venue does not acknowledge, an
 * IOC child with no final report and a cancel with no answer are each chased with a cancel a
 * bounded number of times; a child that still has no answer is flagged unresolved, keeps its
 * quantity reserved, and its parent ends {@link ParentOutcome#Failed}. A parent that cannot place
 * its remainder within a bounded number of retries after its deadline ends {@link
 * ParentOutcome#Expired}. Every ending is reported once to the {@link ParentListener}.
 *
 * <p>A venue whose book is not trustworthy (a feed gap, a session down, a book older than the age
 * limit) is excluded from every decision with {@code VenueExclusion.Stale}, and children resting
 * there are cancelled ({@link #venueHealth}).
 *
 * <p>Two decision models. Built without an {@link ExecutionModel} the router runs the touch model
 * above, exactly as in earlier releases (the ledger's recorded runs depend on it). Built with one
 * it runs the ladder model (DECISIONS.md DR-R6-R01 to R04): the aggressive leg is a consolidated,
 * fee-adjusted sweep across venues with each child sized to displayed depth and limited at the
 * last level it needs ({@link LadderAllocator}); posting is chosen over crossing on expected cost
 * plus a charge for the price risk of the time the order stays open; the fill probability uses
 * market order and cancellation rates the router measures itself from trade prints and book
 * decrements, calibrated on its own outcomes ({@link FillCalibration}); a {@link VenueScorecard}
 * adds a penalty to each venue's all-in price, throttles it and excludes it on an outage; slice
 * gaps are gamma distributed. Everything a ladder decision used is journaled with it.
 *
 * <p>Our own orders are never crossed: the router's children on the other side and any order
 * registered with {@link #registerOwnOrder} (another strategy under the same CompID) cap the
 * aggressive limit one tick inside them, and are taken out of the displayed size. Children are
 * indexed by venue and side, so a book update or a self-match check looks only at that venue.
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
  private static final long CHILD = 4L;
  private static final int MAX_EXTERNAL = 256;

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
  private final RouterTimeouts timeouts;
  private ParentListener parentListener = ParentListener.NONE;

  // the ladder model; null for the touch model
  private final ExecutionModel model;
  private final VenueScorecard scorecard;
  private final FillCalibration calibration;
  private final boolean[] vOutage = new boolean[CostTable.MAX_VENUES];
  private final TimeInForce[] aggressiveTif = new TimeInForce[CostTable.MAX_VENUES];

  // children by venue row and side: bit cs of words [(row * 2 + side) * words, ...) is set from
  // open to release, so a venue's children are found without scanning every slot
  private final int words;
  private final long[] childBits;

  // orders of other strategies under our CompID, registered by the owner
  private final Long2LongHashMap externalSlot = new Long2LongHashMap(MAX_EXTERNAL * 2, 0.65f, NONE);
  private final long[] xKey = new long[MAX_EXTERNAL];
  private final int[] xRow = new int[MAX_EXTERNAL];
  private final boolean[] xBuy = new boolean[MAX_EXTERNAL];
  private final long[] xPrice = new long[MAX_EXTERNAL];
  private final long[] xQty = new long[MAX_EXTERNAL];
  private final boolean[] xUsed = new boolean[MAX_EXTERNAL];
  private int xHighWater;

  // per venue row: told unhealthy by the owner, and found too old at the last poll
  private final boolean[] vDown = new boolean[CostTable.MAX_VENUES];
  private final boolean[] vAged = new boolean[CostTable.MAX_VENUES];

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
  private final long[] pOrigQty;
  private final int[] pDeadlineRetries;
  private final ParentOutcome[] pOutcome;
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
  private final long[] pScheduleEnd;
  private final boolean[] pDeadlineMode;
  private final boolean[] pCancelled;

  // per child slot
  private final int[] cParent;
  private final long[] cTimer;
  private final boolean[] cEscalate;
  private final long[] cSent;
  private final long[] cHorizon;
  private final int[] cCancelTries;
  private final boolean[] cUnresolved;
  private final int[] cRow;
  // ladder model, per child: model fill probability and queue bucket at placement, whether it
  // was cancelled at its time limit (an uncensored outcome), the venue's acknowledgement seen,
  // a day order standing in for an IOC, the outcome counted, and the queue estimate
  private final double[] cModelP;
  private final int[] cBucket;
  private final boolean[] cHorizonCancel;
  private final boolean[] cAcked;
  private final boolean[] cEmulatedIoc;
  private final boolean[] cCounted;
  private final double[] cAhead;
  private final long[] cLevelSeen;
  private final long[] cPrints;

  private boolean polling;
  private long nextClOrdId;
  private long nextDecisionId;
  private long decisions;
  private long childrenSent;
  private long parentsCompleted;
  private long parentsExpired;
  private long parentsFailed;
  private long unresolvedChildren;
  private long orphanFillQuantity;
  private long staleExclusions;

  /** A router with {@link RouterTimeouts#DEFAULT}. */
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
    this(params, venues, book, gateway, journal, clock, random, maxParents, maxChildren, clOrdIdBase,
        RouterTimeouts.DEFAULT);
  }

  /** A touch-model router, as in earlier releases. */
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
      final long clOrdIdBase,
      final RouterTimeouts timeouts) {
    this(params, venues, book, gateway, journal, clock, random, maxParents, maxChildren, clOrdIdBase,
        timeouts, null);
  }

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
   * @param timeouts bounds on waiting for venues and on retries past the deadline
   * @param model the ladder model's parameters, or null for the touch model
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
      final long clOrdIdBase,
      final RouterTimeouts timeouts,
      final ExecutionModel model) {
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
    this.timeouts = timeouts;
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
    pOrigQty = new long[maxParents];
    pDeadlineRetries = new int[maxParents];
    pOutcome = new ParentOutcome[maxParents];
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
    pScheduleEnd = new long[maxParents];
    pDeadlineMode = new boolean[maxParents];
    pCancelled = new boolean[maxParents];
    cParent = new int[maxChildren];
    cTimer = new long[maxChildren];
    cEscalate = new boolean[maxChildren];
    cSent = new long[maxChildren];
    cHorizon = new long[maxChildren];
    cCancelTries = new int[maxChildren];
    cUnresolved = new boolean[maxChildren];
    cRow = new int[maxChildren];
    cModelP = new double[maxChildren];
    cBucket = new int[maxChildren];
    cHorizonCancel = new boolean[maxChildren];
    cAcked = new boolean[maxChildren];
    cEmulatedIoc = new boolean[maxChildren];
    cCounted = new boolean[maxChildren];
    cAhead = new double[maxChildren];
    cLevelSeen = new long[maxChildren];
    cPrints = new long[maxChildren];
    this.words = (maxChildren + 63) >>> 6;
    this.childBits = new long[venues.length * 2 * words];
    this.nextClOrdId = clOrdIdBase;
    this.nextDecisionId = 1;
    this.model = model;
    if (model != null) {
      this.scorecard = new VenueScorecard(this.venues, model, clock.nanoTime());
      this.calibration = new FillCalibration(venues.length, model.calibrationDecay());
      for (int r = 0; r < venues.length; r++) {
        final VenueCapabilities c = model.capabilities(venues[r].venueId());
        aggressiveTif[r] =
            c.immediateOrCancel()
                ? TimeInForce.ImmediateOrCancel
                : c.fillOrKill() ? TimeInForce.FillOrKill : TimeInForce.Day;
      }
    } else {
      this.scorecard = null;
      this.calibration = null;
    }
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
    pOrigQty[ps] = quantity;
    pDeadlineRetries[ps] = 0;
    pOutcome[ps] = ParentOutcome.Filled;
    pFilled[ps] = 0;
    pNotional[ps] = 0.0;
    pOutstanding[ps] = 0;
    pRetryQty[ps] = 0;
    pUrgency[ps] = Math.round(urgency * SCALE);
    pLimit[ps] = limitPrice;
    if (model != null && limitPrice > 0) {
      // a limit between ticks would put a child off the venue's grid: round it inwards
      final long tick = venues[0].tickMantissa();
      pLimit[ps] = side == Side.Buy ? limitPrice - Math.floorMod(limitPrice, tick)
          : limitPrice + Math.floorMod(-limitPrice, tick);
    }
    pSliceIndex[ps] = 0;
    pDeadlineMode[ps] = false;
    pCancelled[ps] = false;
    pSliceTimer[ps] = NONE;
    pRetryTimer[ps] = NONE;
    pArrivalMid[ps] = model == null ? referenceMid() : consolidatedMid();

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
    pScheduleEnd[ps] = now + horizon;
    pDeadlineTimer[ps] =
        schedule(now + horizon + passive + params.retryDelayNanos(), DEADLINE, ps);
    runSlice(ps);
  }

  /**
   * Cancels the unfilled remainder of a parent (the hedger's {@code HedgeOrderSink.cancel}, bound
   * at integration, DR-R2W-07): routing stops, every live child gets a cancel, and the parent
   * finishes once each child has filled or confirmed its cancel. Quantity a child fills before its
   * cancel lands still counts. Returns false if the parent is not live.
   */
  public boolean cancel(final long parentId) {
    final int ps = (int) parentSlot.get(parentId);
    if (ps < 0) {
      return false;
    }
    if (!pCancelled[ps]) {
      stop(ps, ParentOutcome.Cancelled);
    }
    return true;
  }

  /**
   * Stops routing a parent: no more slices or retries, every live child gets a cancel, and the
   * parent finishes with {@code outcome} once its children have answered. A second call can only
   * worsen the outcome to Failed.
   */
  private void stop(final int ps, final ParentOutcome outcome) {
    if (pCancelled[ps]) {
      if (outcome == ParentOutcome.Failed) {
        pOutcome[ps] = outcome;
      }
      maybeFinish(ps);
      return;
    }
    pCancelled[ps] = true;
    pOutcome[ps] = outcome;
    cancelTimer(pSliceTimer[ps]);
    cancelTimer(pRetryTimer[ps]);
    cancelTimer(pDeadlineTimer[ps]);
    pSliceTimer[ps] = NONE;
    pRetryTimer[ps] = NONE;
    pDeadlineTimer[ps] = NONE;
    pSlicesLeft[ps] = 0;
    pRetryQty[ps] = 0;
    pQty[ps] = pFilled[ps] + pOutstanding[ps];
    final int hw = children.highWater();
    for (int cs = 0; cs < hw; cs++) {
      if (children.inUse(cs)
          && cParent[cs] == ps
          && !cUnresolved[cs]
          && ChildOrderBook.isLive(children.state(cs))) {
        cancelChild(cs, false, false);
      }
    }
    maybeFinish(ps);
  }

  /** Finishes the parent if nothing can still fill, or if only unresolved children remain. */
  private void maybeFinish(final int ps) {
    if (pOutstanding[ps] == 0) {
      // after an overfill the parent can be filled past its quantity
      if (pFilled[ps] >= pQty[ps]) {
        finishParent(ps);
      }
    } else if (pOutcome[ps] == ParentOutcome.Failed && pOutstanding[ps] == unresolvedLeaves(ps)) {
      finishParent(ps);
    }
  }

  private long unresolvedLeaves(final int ps) {
    long sum = 0;
    final int hw = children.highWater();
    for (int cs = 0; cs < hw; cs++) {
      if (children.inUse(cs) && cParent[cs] == ps && cUnresolved[cs]) {
        sum += children.leaves(cs);
      }
    }
    return sum;
  }

  /** Sets who is told when a parent ends; {@link ParentListener#NONE} by default. */
  public void parentListener(final ParentListener listener) {
    this.parentListener = listener;
  }

  /**
   * Tells the router whether a venue's book and session can be trusted (the owner knows: a feed
   * gap, a snapshot in progress, a session down). An unhealthy venue is excluded from every
   * decision with {@code VenueExclusion.Stale}, and children resting there are cancelled so their
   * quantity can go elsewhere. Unknown venues are ignored.
   */
  public void venueHealth(final int venueId, final boolean healthy) {
    final int r = rowOf(venueId);
    if (r < 0 || vDown[r] == !healthy) {
      return;
    }
    vDown[r] = !healthy;
    if (!healthy) {
      cancelResting(venueId);
    }
  }

  private void cancelResting(final int venueId) {
    final int r = rowOf(venueId);
    if (r < 0) {
      return;
    }
    final int b0 = (r * 2) * words;
    final int b1 = (r * 2 + 1) * words;
    for (int w = 0; w < words; w++) {
      long bits = childBits[b0 + w] | childBits[b1 + w];
      while (bits != 0) {
        final int cs = (w << 6) + Long.numberOfTrailingZeros(bits);
        bits &= bits - 1;
        cancelRestingChild(cs, venueId);
      }
    }
  }

  private void cancelRestingChild(final int cs, final int venueId) {
    if (children.inUse(cs)
        && children.passive(cs)
        && children.venueId(cs) == venueId
        && cParent[cs] >= 0
        && !cUnresolved[cs]
        && ChildOrderBook.isLive(children.state(cs))) {
      cancelChild(cs, true, false);
    }
  }

  private boolean tooOld(final int r) {
    final long maxAge = timeouts.maxBookAgeNanos();
    if (maxAge <= 0) {
      return false;
    }
    final long last = book.lastUpdateNanos(venues[r].venueId());
    return last != BookView.AGE_UNKNOWN && clock.epochNanos() - last > maxAge;
  }

  private boolean stale(final int r) {
    return vDown[r] || tooOld(r);
  }

  /** Cancels every live parent, as {@link #cancel} does for one; the kill switch's path. */
  public int cancelAll() {
    int n = 0;
    for (int ps = 0; ps < maxParents; ps++) {
      if (parentSlot.get(pId[ps]) == ps && !pCancelled[ps] && cancel(pId[ps])) {
        n++;
      }
    }
    return n;
  }

  /** Fires due timers. Call from the owning loop as often as it runs. */
  public int poll() {
    final int fired;
    polling = true;
    try {
      fired = wheel.poll(clock.nanoTime());
    } finally {
      polling = false;
    }
    if (timeouts.maxBookAgeNanos() > 0) {
      for (int r = 0; r < venues.length; r++) {
        final boolean old = tooOld(r);
        if (old && !vAged[r]) {
          // once per episode: the book went quiet with our orders resting on it
          cancelResting(venues[r].venueId());
        }
        vAged[r] = old;
      }
    }
    if (model != null) {
      final long now = clock.nanoTime();
      scorecard.poll(now, book, venues);
      for (int r = 0; r < venues.length; r++) {
        final boolean out = scorecard.inOutage(r, now);
        if (out && !vOutage[r]) {
          // once per outage: what rests there goes elsewhere
          cancelResting(venues[r].venueId());
        }
        vOutage[r] = out;
      }
    }
    return fired;
  }

  /**
   * Registers, or updates, an order of another strategy under the same CompID resting at a venue
   * (the quoter's, for example), so the router neither crosses it nor counts it as liquidity
   * (review finding S13). {@code key} is the owner's id for the order; a quantity of 0 removes
   * it. Returns false if the venue is not one of the router's or the registry is full.
   */
  public boolean registerOwnOrder(
      final long key, final int venueId, final Side side, final long price, final long quantity) {
    final int r = rowOf(venueId);
    if (r < 0 || price <= 0 || (side != Side.Buy && side != Side.Sell)) {
      return false;
    }
    if (quantity <= 0) {
      return removeOwnOrder(key);
    }
    int x = (int) externalSlot.get(key);
    if (x < 0) {
      x = -1;
      for (int i = 0; i < MAX_EXTERNAL; i++) {
        if (!xUsed[i]) {
          x = i;
          break;
        }
      }
      if (x < 0) {
        return false;
      }
      externalSlot.put(key, x);
      xUsed[x] = true;
      xHighWater = Math.max(xHighWater, x + 1);
    }
    xKey[x] = key;
    xRow[x] = r;
    xBuy[x] = side == Side.Buy;
    xPrice[x] = price;
    xQty[x] = quantity;
    return true;
  }

  /** Removes a registered own order; false if it was not registered. */
  public boolean removeOwnOrder(final long key) {
    final int x = (int) externalSlot.remove(key);
    if (x < 0) {
      return false;
    }
    xUsed[x] = false;
    return true;
  }

  /** Own orders of other strategies registered now. */
  public int registeredOwnOrders() {
    int n = 0;
    for (int i = 0; i < xHighWater; i++) {
      if (xUsed[i]) {
        n++;
      }
    }
    return n;
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
      if (model != null) {
        scoreReport(cs, ordStatus);
      }
      afterChildChange(cs);
    }
  }

  /** Feeds the scorecard from one report of a child (ladder model). */
  private void scoreReport(final int cs, final OrdStatus ordStatus) {
    final int r = cRow[cs];
    final long now = clock.nanoTime();
    final ChildState st = children.state(cs);
    if (ordStatus == OrdStatus.Rejected && st == ChildState.Rejected) {
      scorecard.onReject(r, now);
    } else if (!cAcked[cs] && ordStatus != OrdStatus.Rejected) {
      cAcked[cs] = true;
      scorecard.onAck(r, now, now - cSent[cs]);
      if (cEmulatedIoc[cs] && ChildOrderBook.isLive(st)) {
        // a day order standing in for an IOC: what did not trade on arrival is cancelled now
        cancelChild(cs, true, false);
      }
    }
    final long fill = children.lastFill();
    if (fill > 0) {
      final int v = venues[r].venueId();
      final long bid = book.bestBid(v);
      final long ask = book.bestAsk(v);
      scorecard.onFill(r, now, children.side(cs), bid > 0 && ask > bid ? (bid + ask) / 2 : 0L);
    }
  }

  /** Counts a finished child once: its IOC fill ratio, or its passive outcome (ladder model). */
  private void countOutcome(final int cs) {
    if (model == null || cCounted[cs] || !ChildOrderBook.isTerminal(children.state(cs))) {
      return;
    }
    cCounted[cs] = true;
    final int r = cRow[cs];
    final long ordered = children.ordered(cs);
    if (!children.passive(cs)) {
      scorecard.onIocDone(r, clock.nanoTime(), ordered, children.cum(cs));
    } else if (children.state(cs) == ChildState.Filled || cHorizonCancel[cs]) {
      // cancelled at its time limit or filled: an outcome; any other cancel censors it
      calibration.record(r, cBucket[cs], cModelP[cs], children.cum(cs) / (double) ordered);
    }
  }

  @Override
  public void onCancelReject(final int venueId, final long clOrdId) {
    final int cs = children.slotOf(clOrdId);
    if (cs >= 0 && children.onCancelReject(venueId, clOrdId)) {
      cEscalate[cs] = false;
      if (model != null) {
        scorecard.onCancelReject(cRow[cs], clock.nanoTime());
      }
      // A venue usually refuses a cancel because the order has finished, and then its fills are
      // on their way. They get one more wait; after it the child is cancelled again or flagged.
      armChild(cs, clock.nanoTime() + timeouts.cancelNanos());
    }
  }

  @Override
  public void onBookUpdate(final int venueId) {
    final int r = rowOf(venueId);
    if (r < 0 || stale(r)) {
      // an untrusted book is not a reason to chase the touch
      return;
    }
    final long bid = book.bestBid(venueId);
    final long ask = book.bestAsk(venueId);
    final long threshold = params.bookMoveThresholdTicks() * venues[r].tickMantissa();
    if (model != null) {
      final long now = clock.nanoTime();
      scorecard.onTouch(
          r, now, Side.Buy, bid, othersAt(r, Side.Buy, bid, book.depth(venueId, Side.Buy, 0)));
      scorecard.onTouch(
          r, now, Side.Sell, ask, othersAt(r, Side.Sell, ask, book.depth(venueId, Side.Sell, 0)));
    }
    // only this venue's children, in slot order (both sides' bits merged)
    final int b0 = (r * 2) * words;
    final int b1 = (r * 2 + 1) * words;
    for (int w = 0; w < words; w++) {
      long bits = childBits[b0 + w] | childBits[b1 + w];
      while (bits != 0) {
        final int cs = (w << 6) + Long.numberOfTrailingZeros(bits);
        bits &= bits - 1;
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
          cancelChild(cs, true, false);
        } else if (model != null) {
          trackQueue(cs, venueId);
        }
      }
    }
  }

  /** Displayed size at a touch less our own resting size there. */
  private long othersAt(final int r, final Side side, final long price, final long displayed) {
    return price <= 0 ? 0L : Math.max(0L, displayed - ownSizeAt(r, side, price));
  }

  /**
   * Updates a resting child's estimate of the queue ahead from its level's displayed size: a
   * decrement that trade prints at that price explain was trading (already taken off the queue by
   * {@link #onTrade}); the rest was cancellation, taken pro rata from the orders of others ahead
   * of and behind us. An increment joined behind us and changes nothing.
   */
  private void trackQueue(final int cs, final int venueId) {
    final Side side = children.side(cs);
    final long px = children.price(cs);
    long size = 0;
    for (int k = 0; k < CostTable.MAX_LEVELS; k++) {
      final long p = book.levelPrice(venueId, side, k);
      if (p <= 0) {
        break;
      }
      if (p == px) {
        size = Math.max(0L, book.depth(venueId, side, k));
        break;
      }
    }
    final long seen = cLevelSeen[cs];
    if (size < seen) {
      final long d = seen - size;
      final long explained = Math.min(d, cPrints[cs]);
      cPrints[cs] -= explained;
      final long cancels = d - explained;
      final long others = seen - children.leaves(cs);
      if (cancels > 0 && others > 0) {
        cAhead[cs] = Math.max(0.0, cAhead[cs] - (double) cancels * cAhead[cs] / others);
      }
    }
    cLevelSeen[cs] = size;
  }

  @Override
  public void onTrade(
      final int venueId, final Side aggressorSide, final long price, final long quantity) {
    if (model == null) {
      return;
    }
    final int r = rowOf(venueId);
    if (r < 0) {
      return;
    }
    scorecard.onTrade(r, clock.nanoTime(), aggressorSide, price, quantity);
    // a print at a resting child's price took that much off the queue ahead of it; one through
    // its price means nothing is left ahead
    final Side resting = aggressorSide == Side.Sell ? Side.Buy : Side.Sell;
    final int base = (r * 2 + (resting == Side.Buy ? 0 : 1)) * words;
    for (int w = 0; w < words; w++) {
      long bits = childBits[base + w];
      while (bits != 0) {
        final int cs = (w << 6) + Long.numberOfTrailingZeros(bits);
        bits &= bits - 1;
        if (!children.inUse(cs) || !children.passive(cs)) {
          continue;
        }
        final long px = children.price(cs);
        if (px == price) {
          cAhead[cs] = Math.max(0.0, cAhead[cs] - quantity);
          cPrints[cs] += quantity;
        } else if (resting == Side.Buy ? price < px : price > px) {
          cAhead[cs] = 0.0;
        }
      }
    }
  }

  /** Estimated quantity ahead of a resting child in its venue queue, or -1 (ladder model only). */
  public double queueAhead(final long clOrdId) {
    final int cs = children.slotOf(clOrdId);
    return cs < 0 || model == null || !children.passive(cs) ? -1.0 : cAhead[cs];
  }

  /**
   * Mass status reconciliation for one venue after a reconnect. Children the venue reports
   * finished are closed and their quantity re-routed; working orders the venue reports that we do
   * not hold as live are cancelled.
   *
   * <p>A child the venue does not list frees nothing. A mass status lists live orders, so an
   * order that filled while the session was down is absent from it exactly as one the venue never
   * received; reading absence as cancelled with nothing filled would hedge that quantity twice.
   * Such a child keeps its quantity reserved and is sent a cancel: the venue's answer for that one
   * order (a cancel confirmation carrying its cumulative quantity, or a refusal) is what settles
   * it, and with no answer it ends unresolved and fails its parent. Off the hot path: allocates.
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
        if (cParent[cs] >= 0 && !cUnresolved[cs]) {
          if (ChildOrderBook.isLive(children.state(cs))) {
            cancelChild(cs, true, false);
          } else if (cTimer[cs] == NONE) {
            sendCancel(cs);
          }
        }
      } else {
        freed = children.reconcile(children.clOrdId(cs), true, s.status(), s.cumQuantity());
        if (ChildOrderBook.isTerminal(children.state(cs))) {
          closed++;
        }
      }
      released += freed;
      if (freed > 0) {
        final int ps = cParent[cs];
        cancelTimer(cTimer[cs]);
        cTimer[cs] = NONE;
        if (ps >= 0) {
          pOutstanding[ps] -= freed;
          if (pCancelled[ps]) {
            pQty[ps] -= freed;
          } else {
            final long done = decide(ps, freed, RouteReason.Reconcile, passiveAllowed(ps));
            addRetry(ps, freed - done);
          }
        }
        releaseIfDone(cs);
        if (ps >= 0) {
          maybeFinish(ps);
        }
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
    if (ps < 0) {
      // the parent already ended Failed over this child: the fill is still a position, which the
      // owner books from the child book; here it is only counted
      orphanFillQuantity += fill;
      releaseIfDone(cs);
      return;
    }
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
    if (children.inUse(cs)
        && cTimer[cs] == NONE
        && !cUnresolved[cs]
        && ChildOrderBook.isTerminal(children.state(cs))) {
      // finished on the venue with fills still to be reported: they get one wait, like a cancel
      armChild(cs, clock.nanoTime() + timeouts.cancelNanos());
    }
    // the parent finishes once nothing is left outstanding on its children, so no live child
    // keeps a freed parent slot
    maybeFinish(ps);
  }

  private void onFreed(final int cs, final int ps, final long freed) {
    if (pCancelled[ps]) {
      pQty[ps] -= freed;
      return;
    }
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
    countOutcome(cs);
    if (ChildOrderBook.isTerminal(children.state(cs)) && children.leaves(cs) == 0) {
      cancelTimer(cTimer[cs]);
      cTimer[cs] = NONE;
      cUnresolved[cs] = false;
      children.release(cs);
      final int b = (cRow[cs] * 2 + (children.side(cs) == Side.Buy ? 0 : 1)) * words + (cs >>> 6);
      childBits[b] &= ~(1L << (cs & 63));
    }
  }

  private void finishParent(final int ps) {
    // children still in the book here are unresolved ones: they outlive the parent as orphans
    long unresolved = 0;
    final int hw = children.highWater();
    for (int cs = 0; cs < hw; cs++) {
      if (children.inUse(cs) && cParent[cs] == ps) {
        unresolved += children.leaves(cs);
        cParent[cs] = -1;
      }
    }
    final ParentOutcome outcome = pOutcome[ps];
    if (outcome == ParentOutcome.Expired) {
      parentsExpired++;
    } else if (outcome == ParentOutcome.Failed) {
      parentsFailed++;
    }
    cancelTimer(pSliceTimer[ps]);
    cancelTimer(pRetryTimer[ps]);
    cancelTimer(pDeadlineTimer[ps]);
    pSliceTimer[ps] = NONE;
    pRetryTimer[ps] = NONE;
    pDeadlineTimer[ps] = NONE;
    parentSlot.remove(pId[ps]);
    parentFree[parentFreeTop++] = ps;
    parentsCompleted++;
    parentListener.onParentDone(pId[ps], outcome, pOrigQty[ps], pFilled[ps], unresolved);
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
      final long now = clock.nanoTime();
      final long gap;
      if (model == null) {
        gap = randomiser.interval(pSliceInterval[ps]);
      } else {
        // gamma gaps around what is left of the schedule per slice left: no fixed period
        final long mean = Math.max(1L, (pScheduleEnd[ps] - now) / pSlicesLeft[ps]);
        gap = randomiser.gammaGap(mean, model.scheduleShape());
      }
      pSliceTimer[ps] = schedule(now + gap, SLICE, ps);
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
    if (routed < want
        && pDeadlineMode[ps]
        && ++pDeadlineRetries[ps] > timeouts.maxDeadlineRetries()) {
      // past the deadline and still unplaceable: stop, rather than decide and journal for ever
      stop(ps, ParentOutcome.Expired);
      return;
    }
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
        cancelChild(cs, true, true);
      }
    }
    final long want = unrouted(ps);
    pRetryQty[ps] = 0;
    cancelTimer(pRetryTimer[ps]);
    pRetryTimer[ps] = NONE;
    final long routed = decide(ps, want, RouteReason.Deadline, false);
    addRetry(ps, want - routed);
  }

  /**
   * Requests the cancel of a live child. {@code replace} marks the quantity for re-routing,
   * {@code escalate} for re-routing aggressively.
   */
  private void cancelChild(final int cs, final boolean replace, final boolean escalate) {
    if (children.requestCancel(children.clOrdId(cs), replace)) {
      cEscalate[cs] = escalate;
      sendCancel(cs);
    }
  }

  /** Sends a cancel and waits a bounded time for the venue's answer. */
  private void sendCancel(final int cs) {
    cCancelTries[cs]++;
    if (model != null) {
      scorecard.onCancelSent(cRow[cs], clock.nanoTime());
    }
    gateway.cancel(children.venueId(cs), children.clOrdId(cs));
    armChild(cs, clock.nanoTime() + timeouts.cancelNanos());
  }

  private void armChild(final int cs, final long deadline) {
    cancelTimer(cTimer[cs]);
    cTimer[cs] = schedule(deadline, CHILD, cs);
  }

  /**
   * The one timer every child carries. What it means depends on where the child is: waiting for
   * an acknowledgement, resting until its passive horizon, an IOC waiting for its final report,
   * or waiting for the answer to a cancel.
   */
  private void onChildTimer(final int cs) {
    cTimer[cs] = NONE;
    if (!children.inUse(cs) || cUnresolved[cs]) {
      return;
    }
    final ChildState st = children.state(cs);
    if (ChildOrderBook.isTerminal(st)) {
      // the venue said it finished with more filled than its reports have shown, and the
      // missing reports have not come
      unresolved(cs);
      return;
    }
    if (st == ChildState.CancelRequested) {
      if (cCancelTries[cs] < timeouts.maxCancelAttempts()) {
        sendCancel(cs);
      } else {
        unresolved(cs);
      }
      return;
    }
    final int ps = cParent[cs];
    final long elapsed = clock.nanoTime() - cSent[cs];
    final boolean passive = children.passive(cs);
    final long limit = passive ? cHorizon[cs] : timeouts.iocNanos();
    final boolean unacknowledged = st == ChildState.New && elapsed >= timeouts.ackNanos();
    final boolean stopped = ps < 0 || pCancelled[ps];
    if (!unacknowledged && !stopped && elapsed < limit) {
      armChild(cs, cSent[cs] + (st == ChildState.New ? Math.min(timeouts.ackNanos(), limit) : limit));
      return;
    }
    if (unacknowledged && model != null) {
      scorecard.onAckTimeout(cRow[cs], clock.nanoTime());
    }
    if (cCancelTries[cs] >= timeouts.maxCancelAttempts()) {
      unresolved(cs);
    } else if (stopped) {
      cancelChild(cs, false, false);
    } else {
      final boolean atLimit = passive && elapsed >= limit;
      cHorizonCancel[cs] = atLimit && !unacknowledged;
      cancelChild(cs, true, atLimit);
    }
  }

  /**
   * The venue has not answered for this child within every bound. Its quantity may have traded,
   * so it stays reserved; the parent stops and ends Failed.
   */
  private void unresolved(final int cs) {
    cUnresolved[cs] = true;
    unresolvedChildren++;
    final int ps = cParent[cs];
    if (ps >= 0) {
      stop(ps, ParentOutcome.Failed);
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
    t.ladder = model != null;
    t.modelVersion = model == null ? 0 : model.modelVersion();
    for (int r = 0; r < venues.length; r++) {
      fillRow(t, r, ps, side);
      if (model != null) {
        fillLadderRow(t, r, side);
      }
    }
    allocator.allocate(t, params, venues, model);
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
    joinPrice[r] = 0;
    aggressivePrice[r] = 0;
    if (stale(r)) {
      // the row keeps what the book showed, for the audit; nothing is sent on it
      t.exclusion[r] = VenueExclusion.Stale;
      staleExclusions++;
      return;
    }
    if (model != null && scorecard.inOutage(r, clock.nanoTime())) {
      t.exclusion[r] = VenueExclusion.Outage;
      return;
    }
    if (bid <= 0 || ask <= bid) {
      t.exclusion[r] = VenueExclusion.NoQuote;
      return;
    }
    if (model != null && scorecard.throttled(r, clock.nanoTime())) {
      t.exclusion[r] = VenueExclusion.Throttled;
      return;
    }
    final long tick = vp.tickMantissa();
    final long join = buy ? bid : ask;
    final long far = buy ? ask : bid;
    long limit = buy ? far + params.aggressiveCollarTicks() * tick : far - params.aggressiveCollarTicks() * tick;
    final long own = ownBestOpposite(r, opposite);
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
   * Our most aggressive live price on {@code side} at a venue row (lowest sell or highest buy),
   * over resting and in-flight children alike, since an IOC in flight and a new resting order can
   * reach the venue in either order, and over the registered orders of other strategies; 0 if
   * none. Looks only at that venue and side.
   */
  private long ownBestOpposite(final int r, final Side side) {
    long best = 0;
    final int base = (r * 2 + (side == Side.Buy ? 0 : 1)) * words;
    for (int w = 0; w < words; w++) {
      long bits = childBits[base + w];
      while (bits != 0) {
        final int cs = (w << 6) + Long.numberOfTrailingZeros(bits);
        bits &= bits - 1;
        if (!children.inUse(cs)) {
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
    }
    final boolean buy = side == Side.Buy;
    for (int x = 0; x < xHighWater; x++) {
      if (xUsed[x] && xRow[x] == r && xBuy[x] == buy) {
        final long px = xPrice[x];
        if (best == 0 || (buy ? px > best : px < best)) {
          best = px;
        }
      }
    }
    return best;
  }

  /** Our own resting quantity at one price on one side of a venue row, ours and registered. */
  private long ownSizeAt(final int r, final Side side, final long price) {
    long size = 0;
    final int base = (r * 2 + (side == Side.Buy ? 0 : 1)) * words;
    for (int w = 0; w < words; w++) {
      long bits = childBits[base + w];
      while (bits != 0) {
        final int cs = (w << 6) + Long.numberOfTrailingZeros(bits);
        bits &= bits - 1;
        if (children.inUse(cs)
            && children.passive(cs)
            && children.price(cs) == price
            && (ChildOrderBook.isLive(children.state(cs))
                || children.state(cs) == ChildState.CancelRequested)) {
          size += children.leaves(cs);
        }
      }
    }
    final boolean buy = side == Side.Buy;
    for (int x = 0; x < xHighWater; x++) {
      if (xUsed[x] && xRow[x] == r && xBuy[x] == buy && xPrice[x] == price) {
        size += xQty[x];
      }
    }
    return size;
  }

  /**
   * The ladder model's inputs for a row: the reachable opposite levels with our own size in them,
   * the row's limit price, and the scorecard and learned queue rates (journaled with the row).
   */
  private void fillLadderRow(final CostTable t, final int r, final Side side) {
    final VenueParams vp = venues[r];
    final int v = vp.venueId();
    final long now = clock.nanoTime();
    final boolean buy = side == Side.Buy;
    final Side opposite = buy ? Side.Sell : Side.Buy;
    t.levelCount[r] = 0;
    t.limitPrice[r] = aggressivePrice[r];
    t.ownOpposite[r] = 0;
    t.rejectRate[r] = Math.round(scorecard.rejectRate(r, now) * SCALE);
    t.cancelRejectRate[r] = Math.round(scorecard.cancelRejectRate(r, now) * SCALE);
    t.fillRatio[r] = Math.round(scorecard.fillRatio(r, now) * SCALE);
    t.markout1s[r] = Math.round(scorecard.markout(r, now, 0) * SCALE);
    t.markout5s[r] = Math.round(scorecard.markout(r, now, 1) * SCALE);
    t.markout30s[r] = Math.round(scorecard.markout(r, now, 2) * SCALE);
    final long ack = scorecard.ackLatency(r, now, 2 * vp.latencyNanos());
    t.ackLatencyNanos[r] = ack;
    // one way is half the acknowledgement round trip, once the router has measured it
    t.latencyNanos[r] = ack / 2;
    t.cancelRate[r] = Math.round(scorecard.cancelRate(r, now) * SCALE);
    // market orders against our resting side, from trade prints; half the book's two-sided rate
    // before the first print
    final double measured = scorecard.marketOrderRate(r, now, side);
    final double rate = measured >= 0.0 ? measured : book.tradeRatePerSecond(v) * 0.5;
    t.tradeRate[r] = rate > 0.0 && Double.isFinite(rate) ? Math.round(rate * SCALE) : 0;
    final int bucket = FillCalibration.bucket(t.sameSideDepth[r] / vp.lotSize());
    t.queueBucket[r] = bucket;
    t.calObserved[r] = Math.round(calibration.observed(r, bucket) * SCALE);
    t.calPredicted[r] = Math.round(calibration.predicted(r, bucket) * SCALE);
    if (t.exclusion[r] == VenueExclusion.Stale
        || t.exclusion[r] == VenueExclusion.Outage
        || t.exclusion[r] == VenueExclusion.NoQuote
        || t.exclusion[r] == VenueExclusion.Throttled) {
      t.limitPrice[r] = 0;
      return;
    }
    t.ownOpposite[r] = ownBestOpposite(r, opposite);
    final long limit = aggressivePrice[r];
    int k = 0;
    for (; k < CostTable.MAX_LEVELS; k++) {
      final long px = book.levelPrice(v, opposite, k);
      if (px <= 0 || (buy ? px > limit : px < limit)) {
        break;
      }
      final int i = r * CostTable.MAX_LEVELS + k;
      t.levelPrice[i] = px;
      t.levelDisplayed[i] = Math.max(0L, book.depth(v, opposite, k));
      t.levelOwn[i] = Math.min(t.levelDisplayed[i], ownSizeAt(r, opposite, px));
    }
    t.levelCount[r] = k;
  }

  private long consolidatedMid() {
    long bestBid = 0;
    long bestAsk = Long.MAX_VALUE;
    for (int r = 0; r < venues.length; r++) {
      final long bid = book.bestBid(venues[r].venueId());
      final long ask = book.bestAsk(venues[r].venueId());
      if (bid > 0 && ask > bid && !stale(r)) {
        bestBid = Math.max(bestBid, bid);
        bestAsk = Math.min(bestAsk, ask);
      }
    }
    return bestBid == 0 || bestAsk == Long.MAX_VALUE ? 0 : (bestBid + bestAsk) / 2;
  }

  private void sendChild(
      final int ps, final int r, final long qty, final boolean passive, final long horizon) {
    final VenueParams vp = venues[r];
    final long id = nextClOrdId++;
    final long price =
        passive ? joinPrice[r] : model != null ? table.childLimit[r] : aggressivePrice[r];
    final int cs = children.open(id, pId[ps], vp.venueId(), pSide[ps], price, qty, passive);
    cParent[cs] = ps;
    cRow[cs] = r;
    final int b = (r * 2 + (pSide[ps] == Side.Buy ? 0 : 1)) * words + (cs >>> 6);
    childBits[b] |= 1L << (cs & 63);
    TimeInForce tif = passive ? TimeInForce.Day : TimeInForce.ImmediateOrCancel;
    cEmulatedIoc[cs] = false;
    cAcked[cs] = false;
    cCounted[cs] = false;
    cHorizonCancel[cs] = false;
    if (model != null) {
      scorecard.onSent(r, clock.nanoTime());
      if (passive) {
        cModelP[cs] = allocator.ladderModelProbability(r);
        cBucket[cs] = table.queueBucket[r];
        cAhead[cs] = table.sameSideDepth[r];
        cLevelSeen[cs] = table.sameSideDepth[r] + qty;
        cPrints[cs] = 0;
      } else {
        tif = aggressiveTif[r];
        cEmulatedIoc[cs] = tif == TimeInForce.Day;
      }
    }
    cEscalate[cs] = false;
    cTimer[cs] = NONE;
    cUnresolved[cs] = false;
    cCancelTries[cs] = 0;
    final long now = clock.nanoTime();
    cSent[cs] = now;
    cHorizon[cs] = passive ? horizon : 0;
    pOutstanding[ps] += qty;
    childrenSent++;
    gateway.newOrder(
        vp.venueId(),
        id,
        pId[ps],
        pInstrument[ps],
        pSide[ps],
        OrdType.Limit,
        tif,
        price,
        qty);
    final long limit = passive ? horizon : timeouts.iocNanos();
    armChild(cs, now + Math.min(timeouts.ackNanos(), limit));
  }

  private long referenceMid() {
    for (int r = 0; r < venues.length; r++) {
      final VenueParams vp = venues[r];
      final long bid = book.bestBid(vp.venueId());
      final long ask = book.bestAsk(vp.venueId());
      if (bid > 0 && ask > bid && !stale(r)) {
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
    if (kind == CHILD) {
      onChildTimer(slot);
    } else if (kind == SLICE) {
      runSlice(slot);
    } else if (kind == RETRY) {
      onRetry(slot);
    } else if (kind == DEADLINE) {
      onDeadline(slot);
    }
  }

  // ------------------------------------------------------------------ queries

  /** Parents live now. */
  public int parentsLive() {
    return maxParents - parentFreeTop;
  }

  /** Most parents live at once. */
  public int maxParents() {
    return maxParents;
  }

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

  /** Parents ended, whatever the outcome. */
  public long parentsCompleted() {
    return parentsCompleted;
  }

  /** Parents ended {@link ParentOutcome#Expired}. */
  public long parentsExpired() {
    return parentsExpired;
  }

  /** Parents ended {@link ParentOutcome#Failed}. */
  public long parentsFailed() {
    return parentsFailed;
  }

  /** Children flagged unresolved since construction: the venue never answered for them. */
  public long unresolvedChildren() {
    return unresolvedChildren;
  }

  /** Whether the child in this slot is flagged unresolved. */
  public boolean isUnresolved(final int slot) {
    return cUnresolved[slot];
  }

  /** Quantity filled on children whose parent had already ended Failed. */
  public long orphanFillQuantity() {
    return orphanFillQuantity;
  }

  /** Venue rows excluded as stale, over every decision. */
  public long staleExclusions() {
    return staleExclusions;
  }

  /** False while the venue is flagged unhealthy or its book is older than the age limit. */
  public boolean venueUsable(final int venueId) {
    final int r = rowOf(venueId);
    return r >= 0 && !stale(r);
  }

  /** The ladder model in force, or null for the touch model. */
  public ExecutionModel model() {
    return model;
  }

  /** The venue scorecard (ladder model), or null. */
  public VenueScorecard scorecard() {
    return scorecard;
  }

  /** The passive fill calibration (ladder model), or null. */
  public FillCalibration calibration() {
    return calibration;
  }

  /** The bounds on waiting in force. */
  public RouterTimeouts timeouts() {
    return timeouts;
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
