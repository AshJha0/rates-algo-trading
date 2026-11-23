package io.ratesalgo.router.sim;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.router.ExecutionListener;
import java.util.SplittableRandom;
import org.agrona.collections.Long2LongHashMap;

/**
 * In-memory CLOB venues around one efficient price, in which everyone's trades move the price:
 * the market of the router benchmark (docs/research/ROUTER_BENCHMARK.md, DECISIONS.md DR-R6-R05).
 *
 * <p>Model, per step of {@code dt}. The efficient price {@code m} (in ticks) diffuses with
 * volatility {@code sigma}. Venue {@code v} anchors its book at best bid {@code B = round(m -
 * s_v / 2)} and best ask {@code B + s_v}; level {@code k} on each side holds other participants'
 * resting lots, {@code top_v + k * step_v} when full. Other participants send market orders on
 * each side of each venue as Poisson processes, of size one lot plus a geometric number; each
 * walks the book from the best price, through our resting orders in time priority, and every lot
 * taken by anyone, us included, moves {@code m} by {@code kappa} ticks in its direction, which is
 * the permanent part of impact. The depth it took is the transient part: each level refills
 * towards its base at {@code rho} per second on top of new orders replacing cancellations, and
 * each resting lot of theirs cancels at {@code theta} per second, a share of it from ahead of
 * our orders. When {@code m} moves the anchor, the side that moved away is re-quoted fresh at the
 * near levels, the side that moved towards loses the levels now inside the new spread, and depth
 * already taken stays taken. A resting order of ours that the new book crosses is filled at its
 * price, which is adverse selection. Our orders and cancels reach the venue after its latency; a
 * venue may reject an order with a fixed probability. Every trade is published as a print.
 *
 * <p>Common random numbers: the efficient-price path, the arrival of other participants' orders
 * and their sizes, and the book's cancellations and refills each draw a fixed number of uniforms
 * per step from their own streams whatever we do, so two strategies run on one seed face the
 * same exogenous market and differ only by their own orders' effects. Nothing allocates after
 * construction.
 */
public final class ImpactMarket implements SimulatedMarket {

  private static final int MAX_ORDERS = 2_048;
  private static final int MAX_EVENTS = 16_384;
  private static final int MAX_PRINTS = 16_384;
  private static final int MAX_PARENTS = 4_096;
  private static final int MAX_POISSON = 200;
  private static final double EWMA_SECONDS = 60.0;
  private static final long NONE = -1L;
  private static final byte FREE = 0;
  private static final byte PENDING = 1;
  private static final byte RESTING = 2;

  private final ImpactConfig cfg;
  private final Clock clock;
  private final SplittableRandom pricePath;
  private final SplittableRandom flow;
  private final SplittableRandom bookRandom;
  private final SplittableRandom venueRandom;
  private final int n;
  private final int levels;
  private final double dt;
  private final long tick;
  private final long lot;
  private final double sqrtDt;
  private final double kappa;

  private final int[] venueId;
  private final int[] spread;
  private final double[] moRate;
  private final double[] geoQ;
  private final double[][] bidDepth;
  private final double[][] askDepth;
  private final double[][] baseDepth;
  private final double[][] scratch;
  private final long[] anchor;
  private final double[] tradeRate;
  private final double[] tradedLots;
  private final boolean[] changed;
  private final double ewmaAlpha;
  private double mid;

  // our orders
  private final Long2LongHashMap orderSlot = new Long2LongHashMap(MAX_ORDERS * 2, 0.65f, NONE);
  private final int[] freeOrders = new int[MAX_ORDERS];
  private int freeTop;
  private int orderHighWater;
  private long nextSeq;
  private final byte[] oPhase = new byte[MAX_ORDERS];
  private final long[] oId = new long[MAX_ORDERS];
  private final long[] oParent = new long[MAX_ORDERS];
  private final int[] oVenue = new int[MAX_ORDERS];
  private final boolean[] oBuy = new boolean[MAX_ORDERS];
  private final boolean[] oIoc = new boolean[MAX_ORDERS];
  private final boolean[] oFok = new boolean[MAX_ORDERS];
  private final long[] oPriceTicks = new long[MAX_ORDERS];
  private final long[] oLeftLots = new long[MAX_ORDERS];
  private final long[] oCumLots = new long[MAX_ORDERS];
  private final double[] oAhead = new double[MAX_ORDERS];
  private final long[] oSeq = new long[MAX_ORDERS];
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

  // trade prints of the step
  private final int[] tVenue = new int[MAX_PRINTS];
  private final boolean[] tBuyAggressor = new boolean[MAX_PRINTS];
  private final long[] tPriceTicks = new long[MAX_PRINTS];
  private final long[] tLots = new long[MAX_PRINTS];
  private int tCount;

  // per-parent accounting
  private final Long2LongHashMap parentIndex = new Long2LongHashMap(MAX_PARENTS * 2, 0.65f, NONE);
  private final long[] psFilled = new long[MAX_PARENTS];
  private final double[] psNotional = new double[MAX_PARENTS];
  private final double[] psFees = new double[MAX_PARENTS];
  private int parentCount;

  private long selfCrosses;
  private long executions;
  private long rejects;
  private long othersLots;
  private long printsDropped;

  /**
   * @param cfg market parameters
   * @param seed seed of every random stream
   * @param clock the simulation clock, advanced by the caller
   */
  public ImpactMarket(final ImpactConfig cfg, final long seed, final Clock clock) {
    this.cfg = cfg;
    this.clock = clock;
    this.pricePath = new SplittableRandom(seed);
    this.flow = new SplittableRandom(seed ^ 0x6A09E667F3BCC909L);
    this.bookRandom = new SplittableRandom(seed ^ 0xBB67AE8584CAA73BL);
    this.venueRandom = new SplittableRandom(seed ^ 0x3C6EF372FE94F82BL);
    this.n = cfg.venueCount();
    this.levels = cfg.levels();
    this.dt = cfg.stepNanos() / 1e9;
    this.sqrtDt = StrictMath.sqrt(dt);
    this.tick = cfg.tickMantissa();
    this.lot = cfg.lotSize();
    this.kappa = cfg.permanentTicksPerLot();
    this.ewmaAlpha = 1.0 - StrictMath.exp(-dt / EWMA_SECONDS);
    venueId = new int[n];
    spread = new int[n];
    moRate = new double[n];
    geoQ = new double[n];
    bidDepth = new double[n][levels];
    askDepth = new double[n][levels];
    baseDepth = new double[n][levels];
    scratch = new double[1][levels];
    anchor = new long[n];
    tradeRate = new double[n];
    tradedLots = new double[n];
    changed = new boolean[n];
    mid = cfg.startMidTicks();
    for (int v = 0; v < n; v++) {
      final ImpactVenueSpec s = cfg.venue(v);
      venueId[v] = s.venueId();
      spread[v] = s.spreadTicks();
      moRate[v] = s.marketOrdersPerSecond() * dt;
      final double extra = s.meanOrderLots() - 1.0;
      geoQ[v] = extra / (1.0 + extra);
      for (int k = 0; k < levels; k++) {
        baseDepth[v][k] = s.topDepthLots() + k * s.depthStepLots();
        bidDepth[v][k] = baseDepth[v][k];
        askDepth[v][k] = baseDepth[v][k];
      }
      anchor[v] = anchorFor(v);
      tradeRate[v] = 2.0 * s.marketOrdersPerSecond() * s.meanOrderLots() * lot;
      changed[v] = true;
    }
    for (int i = 0; i < MAX_ORDERS; i++) {
      freeOrders[i] = MAX_ORDERS - 1 - i;
    }
    freeTop = MAX_ORDERS;
  }

  private long anchorFor(final int v) {
    return Math.round(mid - spread[v] * 0.5);
  }

  // ------------------------------------------------------------------ the step

  @Override
  public void step() {
    processArrivals(clock.nanoTime());
    // the efficient price: one normal per step, whatever happens
    final double u1 = Math.max(Double.MIN_NORMAL, pricePath.nextDouble());
    final double u2 = pricePath.nextDouble();
    mid += cfg.sigmaTicksPerSqrtSecond() * sqrtDt
        * StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * Math.PI * u2);
    for (int v = 0; v < n; v++) {
      tradedLots[v] = 0.0;
    }
    // other participants' market orders: counts and sizes from their own stream
    for (int v = 0; v < n; v++) {
      for (int side = 0; side < 2; side++) {
        final int count = poisson(flow.nextDouble(), moRate[v]);
        for (int i = 0; i < count; i++) {
          final double u = Math.max(Double.MIN_NORMAL, flow.nextDouble());
          final long size =
              1L + (geoQ[v] > 0.0 ? (long) Math.floor(StrictMath.log(u) / StrictMath.log(geoQ[v])) : 0L);
          othersMarketOrder(v, side == 0, Math.min(size, 1_000L));
        }
      }
    }
    // their resting orders: cancellations and new orders, two uniforms per level and side
    for (int v = 0; v < n; v++) {
      final ImpactVenueSpec s = cfg.venue(v);
      for (int k = 0; k < levels; k++) {
        churn(v, true, k, s);
        churn(v, false, k, s);
      }
      changed[v] = true;
    }
    reanchor();
    for (int v = 0; v < n; v++) {
      final double instant = tradedLots[v] * lot / dt;
      tradeRate[v] += ewmaAlpha * (instant - tradeRate[v]);
    }
  }

  private void churn(final int v, final boolean bid, final int k, final ImpactVenueSpec s) {
    final double[] depth = bid ? bidDepth[v] : askDepth[v];
    final double before = depth[k];
    final int cancels = poisson(bookRandom.nextDouble(), s.cancelRatePerSecond() * before * dt);
    final double base = baseDepth[v][k];
    final double inflow = (s.cancelRatePerSecond() * base + s.refillPerSecond() * Math.max(0.0, base - before)) * dt;
    final int arrivals = poisson(bookRandom.nextDouble(), inflow);
    final double cancelled = Math.min(before, cancels);
    if (cancelled > 0.0 && before > 0.0) {
      // cancellations come from anywhere in the queue: our orders there lose their share ahead
      final long px = bid ? anchor[v] - k : anchor[v] + spread[v] + k;
      for (int s2 = 0; s2 < orderHighWater; s2++) {
        if (oPhase[s2] == RESTING && oVenue[s2] == v && oBuy[s2] == bid && oPriceTicks[s2] == px) {
          oAhead[s2] = Math.max(0.0, oAhead[s2] - cancelled * oAhead[s2] / before);
        }
      }
    }
    depth[k] = Math.max(0.0, before - cancelled) + arrivals;
  }

  /** Moves each venue's book to the efficient price's new anchor. */
  private void reanchor() {
    for (int v = 0; v < n; v++) {
      final long a = anchorFor(v);
      final long d = a - anchor[v];
      if (d == 0) {
        continue;
      }
      shiftSide(v, bidDepth[v], -d);
      shiftSide(v, askDepth[v], d);
      anchor[v] = a;
      changed[v] = true;
    }
    for (int s = 0; s < orderHighWater; s++) {
      if (oPhase[s] != RESTING) {
        continue;
      }
      final int v = oVenue[s];
      final boolean through =
          oBuy[s] ? oPriceTicks[s] >= anchor[v] + spread[v] : oPriceTicks[s] <= anchor[v];
      if (through) {
        fill(s, oLeftLots[s], oPriceTicks[s], cfg.venue(v).makerFee());
        finish(s);
      } else {
        oAhead[s] = Math.min(oAhead[s], Math.floor(othersAt(v, oBuy[s], oPriceTicks[s])));
      }
    }
  }

  /** New level k is old level k + d; levels with no old counterpart are quoted fresh. */
  private void shiftSide(final int v, final double[] depth, final long d) {
    final double[] old = scratch[0];
    System.arraycopy(depth, 0, old, 0, levels);
    for (int k = 0; k < levels; k++) {
      final long from = k + d;
      depth[k] = from >= 0 && from < levels ? old[(int) from] : baseDepth[v][k];
    }
  }

  private double othersAt(final int v, final boolean bid, final long priceTicks) {
    final long k = bid ? anchor[v] - priceTicks : priceTicks - anchor[v] - spread[v];
    if (k < 0 || k >= levels) {
      return 0.0;
    }
    return bid ? bidDepth[v][(int) k] : askDepth[v][(int) k];
  }

  // ------------------------------------------------------------------ trading

  /** One of their market orders: {@code buyer} lifts offers, otherwise hits bids. */
  private void othersMarketOrder(final int v, final boolean buyer, final long size) {
    final boolean restingBuy = !buyer;
    long printPrice = Long.MIN_VALUE;
    long printLots = 0;
    for (long i = 0; i < size; i++) {
      long ours = restingBuy ? Long.MIN_VALUE : Long.MAX_VALUE;
      for (int s = 0; s < orderHighWater; s++) {
        if (oPhase[s] == RESTING && oVenue[s] == v && oBuy[s] == restingBuy) {
          ours = restingBuy ? Math.max(ours, oPriceTicks[s]) : Math.min(ours, oPriceTicks[s]);
        }
      }
      final long theirs = othersBest(v, restingBuy);
      final boolean haveOurs = ours != Long.MIN_VALUE && ours != Long.MAX_VALUE;
      if (!haveOurs && theirs == Long.MIN_VALUE) {
        break;
      }
      final long px;
      if (!haveOurs) {
        px = theirs;
      } else if (theirs == Long.MIN_VALUE) {
        px = ours;
      } else {
        px = restingBuy ? Math.max(ours, theirs) : Math.min(ours, theirs);
      }
      int front = -1;
      for (int s = 0; s < orderHighWater; s++) {
        if (oPhase[s] == RESTING && oVenue[s] == v && oBuy[s] == restingBuy && oPriceTicks[s] == px
            && (front < 0 || oAhead[s] < oAhead[front] || (oAhead[s] == oAhead[front] && oSeq[s] < oSeq[front]))) {
          front = s;
        }
      }
      final double others = othersAt(v, restingBuy, px);
      if (front >= 0 && (oAhead[front] < 1.0 || others < 1.0)) {
        fill(front, 1, px, cfg.venue(v).makerFee());
        if (oLeftLots[front] == 0) {
          finish(front);
        }
      } else {
        takeOthers(v, restingBuy, px, 1.0);
        for (int s = 0; s < orderHighWater; s++) {
          if (oPhase[s] == RESTING && oVenue[s] == v && oBuy[s] == restingBuy && oPriceTicks[s] == px) {
            oAhead[s] = Math.max(0.0, oAhead[s] - 1.0);
          }
        }
      }
      othersLots++;
      tradedLots[v] += 1.0;
      mid += buyer ? kappa : -kappa;
      if (px != printPrice && printLots > 0) {
        print(v, buyer, printPrice, printLots);
        printLots = 0;
      }
      printPrice = px;
      printLots++;
    }
    if (printLots > 0) {
      print(v, buyer, printPrice, printLots);
    }
  }

  /** Their best resting price on a side, or MIN_VALUE if the side is empty. */
  private long othersBest(final int v, final boolean bid) {
    final double[] depth = bid ? bidDepth[v] : askDepth[v];
    for (int k = 0; k < levels; k++) {
      if (depth[k] >= 1.0) {
        return bid ? anchor[v] - k : anchor[v] + spread[v] + k;
      }
    }
    return Long.MIN_VALUE;
  }

  private void takeOthers(final int v, final boolean bid, final long px, final double lots) {
    final long k = bid ? anchor[v] - px : px - anchor[v] - spread[v];
    if (k >= 0 && k < levels) {
      final double[] depth = bid ? bidDepth[v] : askDepth[v];
      depth[(int) k] = Math.max(0.0, depth[(int) k] - lots);
    }
  }

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
    if (venueRandom.nextDouble() < cfg.venue(v).rejectProbability()) {
      rejects++;
      pushReport(s, OrdStatus.Rejected, 0, 0);
      finish(s);
      return;
    }
    if (!oFok[s] || fillable(s) >= oLeftLots[s]) {
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
    oSeq[s] = nextSeq++;
    oAhead[s] = Math.floor(othersAt(v, oBuy[s], oPriceTicks[s]));
    pushReport(s, OrdStatus.New, 0, 0);
    changed[v] = true;
    if (oCancelArrive[s] != NONE && oCancelArrive[s] <= clock.nanoTime()) {
      pushReport(s, OrdStatus.Cancelled, 0, 0);
      finish(s);
    }
  }

  private long fillable(final int s) {
    final int v = oVenue[s];
    final boolean buy = oBuy[s];
    long sum = 0;
    for (int k = 0; k < levels; k++) {
      final long px = buy ? anchor[v] + spread[v] + k : anchor[v] - k;
      if (buy ? px > oPriceTicks[s] : px < oPriceTicks[s]) {
        break;
      }
      sum += (long) Math.floor(buy ? askDepth[v][k] : bidDepth[v][k]);
    }
    return sum;
  }

  /** Our aggressive order walks their levels up to its limit; ours on the other side are skipped. */
  private void walk(final int s) {
    final int v = oVenue[s];
    final boolean buy = oBuy[s];
    for (int o = 0; o < orderHighWater; o++) {
      if (oPhase[o] == RESTING && oVenue[o] == v && oBuy[o] != buy
          && (buy ? oPriceTicks[o] <= oPriceTicks[s] : oPriceTicks[o] >= oPriceTicks[s])) {
        selfCrosses++;
      }
    }
    for (int k = 0; k < levels && oLeftLots[s] > 0; k++) {
      final long px = buy ? anchor[v] + spread[v] + k : anchor[v] - k;
      if (buy ? px > oPriceTicks[s] : px < oPriceTicks[s]) {
        break;
      }
      final double[] depth = buy ? askDepth[v] : bidDepth[v];
      final long take = Math.min((long) Math.floor(depth[k]), oLeftLots[s]);
      if (take > 0) {
        depth[k] -= take;
        fill(s, take, px, cfg.venue(v).takerFee());
        print(v, buy, px, take);
        tradedLots[v] += take;
        mid += (buy ? kappa : -kappa) * take;
        changed[v] = true;
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
    changed[oVenue[s]] = true;
    pushReport(s, oLeftLots[s] == 0 ? OrdStatus.Filled : OrdStatus.PartiallyFilled, price, qty);
  }

  private void print(final int v, final boolean buyAggressor, final long priceTicks, final long lots) {
    if (tCount == MAX_PRINTS) {
      printsDropped++;
      return;
    }
    tVenue[tCount] = v;
    tBuyAggressor[tCount] = buyAggressor;
    tPriceTicks[tCount] = priceTicks;
    tLots[tCount] = lots;
    tCount++;
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
    final boolean closed =
        status == OrdStatus.Cancelled || status == OrdStatus.Filled || status == OrdStatus.Rejected;
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
    changed[oVenue[s]] = true;
    oPhase[s] = FREE;
    orderSlot.remove(oId[s]);
    freeOrders[freeTop++] = s;
  }

  /**
   * Poisson count with mean {@code lambda} by inversion of one uniform, so every draw takes
   * exactly one number from its stream.
   */
  static int poisson(final double u, final double lambda) {
    if (!(lambda > 0.0)) {
      return 0;
    }
    double p = StrictMath.exp(-lambda);
    double cdf = p;
    int k = 0;
    while (u > cdf && k < MAX_POISSON) {
      k++;
      p *= lambda / k;
      cdf += p;
    }
    return k;
  }

  // ------------------------------------------------------------------ SimulatedMarket

  @Override
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
    for (int i = 0; i < tCount; i++) {
      listener.onTrade(
          venueId[tVenue[i]], tBuyAggressor[i] ? Side.Buy : Side.Sell, tPriceTicks[i] * tick,
          tLots[i] * lot);
    }
    tCount = 0;
    for (int v = 0; v < n; v++) {
      if (changed[v]) {
        changed[v] = false;
        listener.onBookUpdate(venueId[v]);
      }
    }
  }

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
    oFok[s] = timeInForce == TimeInForce.FillOrKill;
    oIoc[s] = timeInForce == TimeInForce.ImmediateOrCancel || ordType == OrdType.Market || oFok[s];
    oPriceTicks[s] =
        ordType == OrdType.Market
            ? (buy ? Long.MAX_VALUE / 4 : 0)
            : (buy ? Math.floorDiv(price, tick) : -Math.floorDiv(-price, tick));
    oLeftLots[s] = quantity / lot;
    oCumLots[s] = 0;
    oAhead[s] = 0.0;
    oArrive[s] = clock.nanoTime() + cfg.venue(v).latencyNanos();
    oCancelArrive[s] = NONE;
    orderSlot.put(clOrdId, s);
    if (parentIndex.get(parentId) == NONE) {
      if (parentCount == MAX_PARENTS) {
        throw new IllegalStateException("simulator parent capacity exhausted");
      }
      parentIndex.put(parentId, parentCount++);
    }
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

  /** Price of the {@code level}-th non-empty level of a side, theirs and ours together; 0 if none. */
  @Override
  public long levelPrice(final int venue, final Side side, final int level) {
    final int v = indexOf(venue);
    return v < 0 || level < 0 ? 0L : displayed(v, side == Side.Buy, level, true) * tick;
  }

  @Override
  public long depth(final int venue, final Side side, final int level) {
    final int v = indexOf(venue);
    return v < 0 || level < 0 ? 0L : displayed(v, side == Side.Buy, level, false) * lot;
  }

  /** The {@code level}-th non-empty displayed level: its price in ticks, or its size in lots. */
  private long displayed(final int v, final boolean bid, final int level, final boolean price) {
    long best = bid ? anchor[v] : anchor[v] + spread[v];
    for (int s = 0; s < orderHighWater; s++) {
      if (oPhase[s] == RESTING && oVenue[s] == v && oBuy[s] == bid) {
        best = bid ? Math.max(best, oPriceTicks[s]) : Math.min(best, oPriceTicks[s]);
      }
    }
    final long last = bid ? anchor[v] - levels + 1 : anchor[v] + spread[v] + levels - 1;
    int seen = 0;
    for (long px = best; bid ? px >= last : px <= last; px += bid ? -1 : 1) {
      long qty = (long) Math.floor(othersAt(v, bid, px));
      for (int s = 0; s < orderHighWater; s++) {
        if (oPhase[s] == RESTING && oVenue[s] == v && oBuy[s] == bid && oPriceTicks[s] == px) {
          qty += oLeftLots[s];
        }
      }
      if (qty > 0) {
        if (seen == level) {
          return price ? px : qty;
        }
        seen++;
      }
    }
    return 0L;
  }

  @Override
  public long bestBid(final int venue) {
    return levelPrice(venue, Side.Buy, 0);
  }

  @Override
  public long bestAsk(final int venue) {
    return levelPrice(venue, Side.Sell, 0);
  }

  @Override
  public double tradeRatePerSecond(final int venue) {
    final int v = indexOf(venue);
    return v < 0 ? 0.0 : tradeRate[v];
  }

  /** The consolidated displayed mid, price mantissa: the arrival reference of the benchmark. */
  @Override
  public long referenceMid() {
    long bid = 0;
    long ask = Long.MAX_VALUE;
    for (int v = 0; v < n; v++) {
      final long b = displayed(v, true, 0, true);
      final long a = displayed(v, false, 0, true);
      if (b > 0) {
        bid = Math.max(bid, b);
      }
      if (a > 0) {
        ask = Math.min(ask, a);
      }
    }
    if (bid == 0 || ask == Long.MAX_VALUE) {
      return Math.round(mid * tick);
    }
    return (bid + ask) * tick / 2;
  }

  /** The efficient price, ticks (for tests). */
  public double efficientMidTicks() {
    return mid;
  }

  @Override
  public long parentFilled(final long parentId) {
    final int i = (int) parentIndex.get(parentId);
    return i < 0 ? 0 : psFilled[i];
  }

  @Override
  public double parentNotional(final long parentId) {
    final int i = (int) parentIndex.get(parentId);
    return i < 0 ? 0.0 : psNotional[i];
  }

  @Override
  public double parentFees(final long parentId) {
    final int i = (int) parentIndex.get(parentId);
    return i < 0 ? 0.0 : psFees[i];
  }

  @Override
  public long selfCrosses() {
    return selfCrosses;
  }

  @Override
  public int liveOrders() {
    return MAX_ORDERS - freeTop;
  }

  @Override
  public int[] venueIds() {
    return venueId.clone();
  }

  /** Our orders the venues rejected. */
  public long rejects() {
    return rejects;
  }

  /** Lots other participants took. */
  public long othersLots() {
    return othersLots;
  }

  /** Fills of ours. */
  public long executions() {
    return executions;
  }

  /** Trade prints dropped because more than the queue holds happened between drains. */
  public long printsDropped() {
    return printsDropped;
  }

  private int indexOf(final int venue) {
    for (int v = 0; v < n; v++) {
      if (venueId[v] == venue) {
        return v;
      }
    }
    return -1;
  }
}
