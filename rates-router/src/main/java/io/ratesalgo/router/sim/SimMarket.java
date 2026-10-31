package io.ratesalgo.router.sim;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.router.BookView;
import io.ratesalgo.router.ExecutionListener;
import io.ratesalgo.router.OrderGateway;
import java.util.SplittableRandom;
import org.agrona.collections.Long2LongHashMap;

/**
 * In-memory CLOB venues for one instrument, stepped in discrete time. It stands in for the P2
 * venue simulators until the coordinator binds them, and it is the market of the back-test.
 *
 * <p>Model, per step of {@code dt}: the common best bid moves one tick up or down, each with
 * probability {@code moveRate * dt}, and every venue's book is rebuilt around it (venue {@code v}
 * quotes bid {@code B} and ask {@code B + spread_v}, with depth {@code top_v + k * step_v} lots at
 * level {@code k}); one-lot market orders arrive on each side of each venue as Poisson processes;
 * each lot queued ahead of one of our resting orders cancels at a constant rate; depleted depth
 * refills towards its base. Our resting orders join the back of their level and fill only when
 * the queue ahead has gone or when the price moves through them (which is adverse selection: the
 * resting buy fills exactly when the market has fallen to it). Our aggressive orders walk the
 * levels up to their limit, deplete the depth they take, and move the common price permanently by
 * {@code permanentTicksPerLot} per lot. Orders and cancels reach the venue after its latency.
 *
 * <p>The exogenous random stream (price moves, arrivals) does not depend on our orders, so two
 * strategies run on the same seed face the same market apart from their own impact. Executions
 * are queued and delivered by {@link #drain}; {@link #newOrder} and {@link #cancel} never call
 * back. Nothing allocates after construction.
 */
public final class SimMarket implements BookView, OrderGateway {

  private static final int MAX_ORDERS = 2_048;
  private static final int MAX_EVENTS = 16_384;
  private static final int MAX_PARENTS = 4_096;
  private static final double EWMA_SECONDS = 60.0;
  private static final long NONE = -1L;

  private static final byte FREE = 0;
  private static final byte PENDING = 1;
  private static final byte RESTING = 2;

  private final SimConfig cfg;
  private final Clock clock;
  private final SplittableRandom exogenous;
  private final SplittableRandom queueRandom;
  private final int venueCount;
  private final int levels;
  private final double dt;
  private final long tick;
  private final long lot;
  private final double moveProbability;
  private final double ewmaAlpha;

  private final int[] venueId;
  private final int[] spread;
  private final double[] arrivalExp;
  private final double[][] bidDepth;
  private final double[][] askDepth;
  private final double[][] baseDepth;
  private final double[] tradeRate;
  private final double[] tradedLots;
  private final boolean[] changed;

  private long bidTicks;
  private double pressure;

  // our orders
  private final Long2LongHashMap orderSlot = new Long2LongHashMap(MAX_ORDERS * 2, 0.65f, NONE);
  private final int[] freeOrders = new int[MAX_ORDERS];
  private int freeTop;
  private int orderHighWater;
  private final byte[] oPhase = new byte[MAX_ORDERS];
  private final long[] oId = new long[MAX_ORDERS];
  private final long[] oParent = new long[MAX_ORDERS];
  private final int[] oVenue = new int[MAX_ORDERS];
  private final boolean[] oBuy = new boolean[MAX_ORDERS];
  private final boolean[] oIoc = new boolean[MAX_ORDERS];
  private final long[] oPriceTicks = new long[MAX_ORDERS];
  private final long[] oLeftLots = new long[MAX_ORDERS];
  private final long[] oCumLots = new long[MAX_ORDERS];
  private final long[] oAhead = new long[MAX_ORDERS];
  private final long[] oArrive = new long[MAX_ORDERS];
  private final long[] oCancelArrive = new long[MAX_ORDERS];

  // execution events
  private final boolean[] eCancelReject = new boolean[MAX_EVENTS];
  private final int[] eVenue = new int[MAX_EVENTS];
  private final long[] eId = new long[MAX_EVENTS];
  private final long[] eExec = new long[MAX_EVENTS];
  private final OrdStatus[] eStatus = new OrdStatus[MAX_EVENTS];
  private final long[] ePrice = new long[MAX_EVENTS];
  private final long[] eLast = new long[MAX_EVENTS];
  private final long[] eLeaves = new long[MAX_EVENTS];
  private final long[] eCum = new long[MAX_EVENTS];
  private final long[] eNanos = new long[MAX_EVENTS];
  private int eHead;
  private int eCount;
  private long nextExecId = 1;

  // per-parent accounting
  private final Long2LongHashMap parentIndex = new Long2LongHashMap(MAX_PARENTS * 2, 0.65f, NONE);
  private final long[] psFilled = new long[MAX_PARENTS];
  private final double[] psNotional = new double[MAX_PARENTS];
  private final double[] psFees = new double[MAX_PARENTS];
  private int parentCount;

  private long selfCrosses;
  private long executions;

  /**
   * @param cfg market parameters
   * @param seed seed of the exogenous stream; the queue-cancellation stream is derived from it
   * @param clock the simulation clock, advanced by the caller
   */
  public SimMarket(final SimConfig cfg, final long seed, final Clock clock) {
    this.cfg = cfg;
    this.clock = clock;
    this.exogenous = new SplittableRandom(seed);
    this.queueRandom = new SplittableRandom(seed ^ 0x5DEECE66DL);
    this.venueCount = cfg.venueCount();
    this.levels = cfg.levels();
    this.dt = cfg.stepNanos() / 1e9;
    this.tick = cfg.tickMantissa();
    this.lot = cfg.lotSize();
    this.moveProbability = cfg.moveRatePerSecond() * dt;
    this.ewmaAlpha = 1.0 - StrictMath.exp(-dt / EWMA_SECONDS);
    venueId = new int[venueCount];
    spread = new int[venueCount];
    arrivalExp = new double[venueCount];
    bidDepth = new double[venueCount][levels];
    askDepth = new double[venueCount][levels];
    baseDepth = new double[venueCount][levels];
    tradeRate = new double[venueCount];
    tradedLots = new double[venueCount];
    changed = new boolean[venueCount];
    for (int v = 0; v < venueCount; v++) {
      final SimVenueSpec s = cfg.venue(v);
      venueId[v] = s.venueId();
      spread[v] = s.spreadTicks();
      arrivalExp[v] = StrictMath.exp(-s.arrivalLotsPerSecond() * dt);
      tradeRate[v] = 2.0 * s.arrivalLotsPerSecond() * lot;
      for (int k = 0; k < levels; k++) {
        baseDepth[v][k] = s.topDepthLots() + k * s.depthStepLots();
      }
    }
    for (int i = 0; i < MAX_ORDERS; i++) {
      freeOrders[i] = MAX_ORDERS - 1 - i;
    }
    freeTop = MAX_ORDERS;
    bidTicks = cfg.startBidTicks();
    resetBooks();
  }

  /** Advances the market by one step to the clock's current time. */
  public void step() {
    final long now = clock.nanoTime();
    processArrivals(now);
    final double u = exogenous.nextDouble();
    if (u < moveProbability) {
      shift(1);
    } else if (u < 2.0 * moveProbability) {
      shift(-1);
    }
    for (int v = 0; v < venueCount; v++) {
      tradedLots[v] = 0.0;
      final int sells = poisson(arrivalExp[v]);
      final int buys = poisson(arrivalExp[v]);
      for (int i = 0; i < sells; i++) {
        marketOrder(v, false);
      }
      for (int i = 0; i < buys; i++) {
        marketOrder(v, true);
      }
    }
    cancelsAhead();
    final double refill = Math.min(1.0, cfg.refillPerSecond() * dt);
    for (int v = 0; v < venueCount; v++) {
      for (int k = 0; k < levels; k++) {
        bidDepth[v][k] += (baseDepth[v][k] - bidDepth[v][k]) * refill;
        askDepth[v][k] += (baseDepth[v][k] - askDepth[v][k]) * refill;
      }
      final double instant = tradedLots[v] * lot / dt;
      tradeRate[v] += ewmaAlpha * (instant - tradeRate[v]);
    }
  }

  /** Delivers queued execution events and book changes to the listener, in order. */
  public void drain(final ExecutionListener listener) {
    while (eCount > 0) {
      final int i = eHead;
      eHead = (eHead + 1) % MAX_EVENTS;
      eCount--;
      if (eCancelReject[i]) {
        listener.onCancelReject(eVenue[i], eId[i]);
      } else {
        listener.onExecutionReport(
            eVenue[i], eId[i], eExec[i], eStatus[i], ePrice[i], eLast[i], eLeaves[i], eCum[i],
            eNanos[i]);
      }
    }
    for (int v = 0; v < venueCount; v++) {
      if (changed[v]) {
        changed[v] = false;
        listener.onBookUpdate(venueId[v]);
      }
    }
  }

  // ------------------------------------------------------------------ OrderGateway

  @Override
  public void newOrder(
      final int venue,
      final long clOrdId,
      final long parentId,
      final int instrumentId,
      final Side side,
      final OrdType ordType,
      final TimeInForce timeInForce,
      final long price,
      final long quantity) {
    final int v = indexOf(venue);
    if (v < 0 || quantity <= 0 || quantity % lot != 0 || freeTop == 0) {
      throw new IllegalArgumentException(
          "order rejected by simulator: venue " + venue + " qty " + quantity);
    }
    final int s = freeOrders[--freeTop];
    orderHighWater = Math.max(orderHighWater, s + 1);
    final boolean buy = side == Side.Buy;
    oPhase[s] = PENDING;
    oId[s] = clOrdId;
    oParent[s] = parentId;
    oVenue[s] = v;
    oBuy[s] = buy;
    oIoc[s] = timeInForce == TimeInForce.ImmediateOrCancel || ordType == OrdType.Market;
    oPriceTicks[s] =
        ordType == OrdType.Market
            ? (buy ? Long.MAX_VALUE / 4 : 0)
            : (buy ? Math.floorDiv(price, tick) : -Math.floorDiv(-price, tick));
    oLeftLots[s] = quantity / lot;
    oCumLots[s] = 0;
    oAhead[s] = 0;
    oArrive[s] = clock.nanoTime() + cfg.venue(v).latencyNanos();
    oCancelArrive[s] = NONE;
    orderSlot.put(clOrdId, s);
    parentSlot(parentId);
  }

  @Override
  public void cancel(final int venue, final long clOrdId) {
    final int s = (int) orderSlot.get(clOrdId);
    if (s < 0) {
      pushCancelReject(venue, clOrdId);
      return;
    }
    if (oCancelArrive[s] == NONE) {
      oCancelArrive[s] = clock.nanoTime() + cfg.venue(oVenue[s]).latencyNanos();
    }
  }

  // ------------------------------------------------------------------ BookView

  @Override
  public long bestBid(final int venue) {
    return indexOf(venue) < 0 ? 0 : bidTicks * tick;
  }

  @Override
  public long bestAsk(final int venue) {
    final int v = indexOf(venue);
    return v < 0 ? 0 : (bidTicks + spread[v]) * tick;
  }

  @Override
  public long depth(final int venue, final Side side, final int level) {
    final int v = indexOf(venue);
    if (v < 0 || level < 0 || level >= levels) {
      return 0;
    }
    final boolean buy = side == Side.Buy;
    final long priceTicks = buy ? bidTicks - level : bidTicks + spread[v] + level;
    long ours = 0;
    for (int s = 0; s < orderHighWater; s++) {
      if (oPhase[s] == RESTING && oVenue[s] == v && oBuy[s] == buy && oPriceTicks[s] == priceTicks) {
        ours += oLeftLots[s];
      }
    }
    final double others = buy ? bidDepth[v][level] : askDepth[v][level];
    return ((long) Math.floor(others) + ours) * lot;
  }

  @Override
  public double tradeRatePerSecond(final int venue) {
    final int v = indexOf(venue);
    return v < 0 ? 0.0 : tradeRate[v];
  }

  // ------------------------------------------------------------------ results

  /** Mid of the first venue, price mantissa; the arrival reference in the back-test. */
  public long referenceMid() {
    return bidTicks * tick + spread[0] * tick / 2;
  }

  /** Quantity filled for a parent. */
  public long parentFilled(final long parentId) {
    final int i = (int) parentIndex.get(parentId);
    return i < 0 ? 0 : psFilled[i];
  }

  /** Sum of fill price (price points) times quantity for a parent. */
  public double parentNotional(final long parentId) {
    final int i = (int) parentIndex.get(parentId);
    return i < 0 ? 0.0 : psNotional[i];
  }

  /** Sum of fees (price points per unit) times quantity for a parent. */
  public double parentFees(final long parentId) {
    final int i = (int) parentIndex.get(parentId);
    return i < 0 ? 0.0 : psFees[i];
  }

  /** Aggressive orders that would have traded against one of our resting orders. */
  public long selfCrosses() {
    return selfCrosses;
  }

  /** Fills executed. */
  public long executions() {
    return executions;
  }

  /** Our orders currently resting or in flight. */
  public int liveOrders() {
    return MAX_ORDERS - freeTop;
  }

  // ------------------------------------------------------------------ internals

  private void processArrivals(final long now) {
    for (int s = 0; s < orderHighWater; s++) {
      if (oPhase[s] == PENDING && oArrive[s] <= now) {
        arrive(s);
      }
      if (oPhase[s] == RESTING && oCancelArrive[s] != NONE && oCancelArrive[s] <= now) {
        pushReport(s, OrdStatus.Cancelled, 0, 0);
        finish(s);
      }
    }
  }

  private void arrive(final int s) {
    final int v = oVenue[s];
    final long ask = bidTicks + spread[v];
    final boolean marketable = oBuy[s] ? oPriceTicks[s] >= ask : oPriceTicks[s] <= bidTicks;
    if (marketable) {
      walk(s);
    }
    if (oLeftLots[s] == 0) {
      finish(s);
      return;
    }
    if (oIoc[s]) {
      pushReport(s, OrdStatus.Cancelled, 0, 0);
      finish(s);
      return;
    }
    oPhase[s] = RESTING;
    oAhead[s] = queueAt(v, oBuy[s], oPriceTicks[s]);
    pushReport(s, OrdStatus.New, 0, 0);
    if (oCancelArrive[s] != NONE && oCancelArrive[s] <= clock.nanoTime()) {
      pushReport(s, OrdStatus.Cancelled, 0, 0);
      finish(s);
    }
  }

  private long queueAt(final int v, final boolean buy, final long priceTicks) {
    final long level = buy ? bidTicks - priceTicks : priceTicks - (bidTicks + spread[v]);
    if (level < 0) {
      return 0;
    }
    if (level >= levels) {
      return (long) Math.floor(baseDepth[v][levels - 1]);
    }
    return (long) Math.floor(buy ? bidDepth[v][(int) level] : askDepth[v][(int) level]);
  }

  private void walk(final int s) {
    final int v = oVenue[s];
    final boolean buy = oBuy[s];
    for (int o = 0; o < orderHighWater; o++) {
      if (oPhase[o] == RESTING
          && oVenue[o] == v
          && oBuy[o] != buy
          && (buy ? oPriceTicks[o] <= oPriceTicks[s] : oPriceTicks[o] >= oPriceTicks[s])) {
        selfCrosses++;
      }
    }
    long taken = 0;
    for (int k = 0; k < levels && oLeftLots[s] > 0; k++) {
      final long px = buy ? bidTicks + spread[v] + k : bidTicks - k;
      if (buy ? px > oPriceTicks[s] : px < oPriceTicks[s]) {
        break;
      }
      final double[] depth = buy ? askDepth[v] : bidDepth[v];
      final long available = (long) Math.floor(depth[k]);
      final long take = Math.min(available, oLeftLots[s]);
      if (take > 0) {
        depth[k] -= take;
        taken += take;
        fill(s, take, px, cfg.venue(v).takerFee());
      }
    }
    if (taken > 0) {
      tradedLots[v] += taken;
      pressure += (buy ? 1.0 : -1.0) * taken * cfg.permanentTicksPerLot();
      while (pressure >= 1.0) {
        pressure -= 1.0;
        shift(1);
      }
      while (pressure <= -1.0) {
        pressure += 1.0;
        shift(-1);
      }
    }
  }

  private void shift(final int ticks) {
    bidTicks += ticks;
    resetBooks();
    for (int s = 0; s < orderHighWater; s++) {
      if (oPhase[s] != RESTING) {
        continue;
      }
      final int v = oVenue[s];
      final boolean through =
          oBuy[s] ? oPriceTicks[s] >= bidTicks + spread[v] : oPriceTicks[s] <= bidTicks;
      if (through) {
        fill(s, oLeftLots[s], oPriceTicks[s], cfg.venue(v).makerFee());
        finish(s);
      } else {
        oAhead[s] = Math.min(oAhead[s], queueAt(v, oBuy[s], oPriceTicks[s]));
      }
    }
  }

  private void resetBooks() {
    for (int v = 0; v < venueCount; v++) {
      System.arraycopy(baseDepth[v], 0, bidDepth[v], 0, levels);
      System.arraycopy(baseDepth[v], 0, askDepth[v], 0, levels);
      changed[v] = true;
    }
  }

  /** One lot arrives: a buy lifts the ask side, a sell hits the bid side. */
  private void marketOrder(final int v, final boolean buyerArrives) {
    tradedLots[v] += 1.0;
    final boolean restingBuy = !buyerArrives;
    final long touch = restingBuy ? bidTicks : bidTicks + spread[v];
    int front = -1;
    for (int s = 0; s < orderHighWater; s++) {
      if (oPhase[s] != RESTING || oVenue[s] != v || oBuy[s] != restingBuy) {
        continue;
      }
      final boolean improving = restingBuy ? oPriceTicks[s] > touch : oPriceTicks[s] < touch;
      if (improving || (oPriceTicks[s] == touch && oAhead[s] == 0)) {
        front = s;
        break;
      }
    }
    if (front >= 0) {
      fill(front, 1, oPriceTicks[front], cfg.venue(v).makerFee());
      if (oLeftLots[front] == 0) {
        finish(front);
      }
      return;
    }
    for (int s = 0; s < orderHighWater; s++) {
      if (oPhase[s] == RESTING
          && oVenue[s] == v
          && oBuy[s] == restingBuy
          && oPriceTicks[s] == touch
          && oAhead[s] > 0) {
        oAhead[s]--;
      }
    }
    final double[] depth = restingBuy ? bidDepth[v] : askDepth[v];
    depth[0] = Math.max(0.0, depth[0] - 1.0);
  }

  private void cancelsAhead() {
    final double rate = cfg.cancelRatePerSecond() * dt;
    for (int s = 0; s < orderHighWater; s++) {
      if (oPhase[s] == RESTING && oAhead[s] > 0) {
        final int k = poissonFrom(queueRandom, StrictMath.exp(-rate * oAhead[s]));
        oAhead[s] = Math.max(0, oAhead[s] - k);
      }
    }
  }

  private void fill(final int s, final long lots, final long priceTicks, final double fee) {
    oLeftLots[s] -= lots;
    oCumLots[s] += lots;
    executions++;
    final long qty = lots * lot;
    final long price = priceTicks * tick;
    final int p = (int) parentIndex.get(oParent[s]);
    psFilled[p] += qty;
    psNotional[p] += price / 1e9 * qty;
    psFees[p] += fee * qty;
    pushReport(
        s, oLeftLots[s] == 0 ? OrdStatus.Filled : OrdStatus.PartiallyFilled, price, qty);
  }

  private void pushReport(final int s, final OrdStatus status, final long price, final long qty) {
    if (eCount == MAX_EVENTS) {
      throw new IllegalStateException("simulator event queue full; drain more often");
    }
    final int i = (eHead + eCount) % MAX_EVENTS;
    eCount++;
    eCancelReject[i] = false;
    eVenue[i] = venueId[oVenue[s]];
    eId[i] = oId[s];
    eExec[i] = nextExecId++;
    eStatus[i] = status;
    ePrice[i] = price;
    eLast[i] = qty;
    final boolean closed = status == OrdStatus.Cancelled || status == OrdStatus.Filled;
    eLeaves[i] = closed ? 0 : oLeftLots[s] * lot;
    eCum[i] = oCumLots[s] * lot;
    eNanos[i] = clock.epochNanos();
  }

  private void pushCancelReject(final int venue, final long clOrdId) {
    if (eCount == MAX_EVENTS) {
      throw new IllegalStateException("simulator event queue full; drain more often");
    }
    final int i = (eHead + eCount) % MAX_EVENTS;
    eCount++;
    eCancelReject[i] = true;
    eVenue[i] = venue;
    eId[i] = clOrdId;
  }

  private void finish(final int s) {
    oPhase[s] = FREE;
    orderSlot.remove(oId[s]);
    freeOrders[freeTop++] = s;
  }

  private void parentSlot(final long parentId) {
    if (parentIndex.get(parentId) != NONE) {
      return;
    }
    if (parentCount == MAX_PARENTS) {
      throw new IllegalStateException("simulator parent capacity " + MAX_PARENTS + " exhausted");
    }
    parentIndex.put(parentId, parentCount++);
  }

  private int indexOf(final int venue) {
    for (int v = 0; v < venueCount; v++) {
      if (venueId[v] == venue) {
        return v;
      }
    }
    return -1;
  }

  private int poisson(final double expMinusLambda) {
    return poissonFrom(exogenous, expMinusLambda);
  }

  private static int poissonFrom(final SplittableRandom random, final double expMinusLambda) {
    int k = 0;
    double p = random.nextDouble();
    while (p > expMinusLambda) {
      k++;
      p *= random.nextDouble();
    }
    return k;
  }
}
