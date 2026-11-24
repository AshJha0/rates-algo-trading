package io.ratesalgo.router.backtest;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.router.sim.SimulatedMarket;

/**
 * A real alternative to the router (docs/research/ROUTER_BENCHMARK.md): the whole remainder rests
 * at the best bid (for a buy) across the venues, the larger displayed size breaking a tie; when
 * the touch moves away from it the order is cancelled and posted again at the new touch; at the
 * deadline it is cancelled and the remainder swept best all-in price first, as {@link
 * SweepBaseline}, and retried until done.
 */
public final class PassiveDeadlineBaseline implements ExecutionStrategy {

  private static final long NONE = -1L;

  private final String name;
  private final int[] venueIds;
  private final double[] takerFees;
  private final long deadlineNanos;
  private final long retryNanos;
  private final long lot;
  private final int maxLevels;
  private final long[] planQty;
  private final long[] planLimit;

  private SimulatedMarket market;
  private Clock clock;
  private long parentId = NONE;
  private Side side = Side.Buy;
  private long quantity;
  private long filled;
  private long deadline;
  private boolean sweeping;
  private long nextRetry;
  private long nextId = 1;

  // the one resting order, or the sweep's children
  private long restingId = NONE;
  private int restingVenue;
  private long restingPrice;
  private long restingQty;
  private boolean cancelSent;
  private int sweepLive;
  private final long[] sweepIds;
  private final long[] sweepQty;
  private final long[] sweepCum;

  /**
   * @param name name in reports
   * @param venueIds venues to rest on and sweep
   * @param takerFees taker fee per venue, price points per unit
   * @param deadlineNanos time from arrival to the sweep
   * @param retryNanos wait before sweeping again what a sweep left
   * @param lot quantity granularity
   * @param maxLevels levels a sweep reads per venue
   */
  public PassiveDeadlineBaseline(
      final String name, final int[] venueIds, final double[] takerFees, final long deadlineNanos,
      final long retryNanos, final long lot, final int maxLevels) {
    this.name = name;
    this.venueIds = venueIds.clone();
    this.takerFees = takerFees.clone();
    this.deadlineNanos = deadlineNanos;
    this.retryNanos = retryNanos;
    this.lot = lot;
    this.maxLevels = maxLevels;
    this.planQty = new long[venueIds.length];
    this.planLimit = new long[venueIds.length];
    this.sweepIds = new long[venueIds.length];
    this.sweepQty = new long[venueIds.length];
    this.sweepCum = new long[venueIds.length];
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public void startDay(final SimulatedMarket simMarket, final Clock simClock, final long seed) {
    this.market = simMarket;
    this.clock = simClock;
    this.parentId = NONE;
    this.restingId = NONE;
    this.sweepLive = 0;
  }

  @Override
  public void submit(final long id, final Side parentSide, final long qty) {
    parentId = id;
    side = parentSide;
    quantity = qty;
    filled = 0;
    deadline = clock.nanoTime() + deadlineNanos;
    sweeping = false;
    nextRetry = 0;
    restingId = NONE;
    sweepLive = 0;
    post();
  }

  private void post() {
    final boolean buy = side == Side.Buy;
    int best = -1;
    long bestPrice = 0;
    long bestSize = -1;
    for (int i = 0; i < venueIds.length; i++) {
      final long px = buy ? market.bestBid(venueIds[i]) : market.bestAsk(venueIds[i]);
      if (px <= 0) {
        continue;
      }
      final long size = market.depth(venueIds[i], side, 0);
      if (best < 0 || (buy ? px > bestPrice : px < bestPrice) || (px == bestPrice && size > bestSize)) {
        best = i;
        bestPrice = px;
        bestSize = size;
      }
    }
    if (best < 0) {
      return;
    }
    restingId = nextId++;
    restingVenue = best;
    restingPrice = bestPrice;
    restingQty = quantity - filled;
    cancelSent = false;
    market.newOrder(
        venueIds[best], restingId, parentId, 1, side, OrdType.Limit, TimeInForce.Day, bestPrice,
        restingQty);
  }

  @Override
  public void poll() {
    if (clock == null || parentId == NONE || filled >= quantity) {
      return;
    }
    final long now = clock.nanoTime();
    if (!sweeping && now >= deadline) {
      sweeping = true;
      if (restingId != NONE && !cancelSent) {
        cancelSent = true;
        market.cancel(venueIds[restingVenue], restingId);
      }
    }
    if (sweeping) {
      if (restingId == NONE && sweepLive == 0 && now >= nextRetry) {
        sweep();
      }
      return;
    }
    if (restingId == NONE) {
      post();
      return;
    }
    if (!cancelSent) {
      final int v = venueIds[restingVenue];
      final long touch = side == Side.Buy ? market.bestBid(v) : market.bestAsk(v);
      final boolean away = side == Side.Buy ? touch > restingPrice : touch > 0 && touch < restingPrice;
      if (away) {
        cancelSent = true;
        market.cancel(v, restingId);
      }
    }
  }

  private void sweep() {
    final long remaining = quantity - filled;
    if (remaining <= 0) {
      return;
    }
    if (SweepBaseline.plan(market, venueIds, takerFees, side, remaining, lot, maxLevels, planQty, planLimit) == 0) {
      nextRetry = clock.nanoTime() + retryNanos;
      return;
    }
    for (int i = 0; i < venueIds.length; i++) {
      sweepIds[i] = NONE;
      if (planQty[i] > 0) {
        sweepIds[i] = nextId++;
        sweepQty[i] = planQty[i];
        sweepCum[i] = 0;
        sweepLive++;
        market.newOrder(
            venueIds[i], sweepIds[i], parentId, 1, side, OrdType.Limit,
            TimeInForce.ImmediateOrCancel, planLimit[i], planQty[i]);
      }
    }
  }

  @Override
  public boolean isDone(final long id) {
    return id != parentId || filled >= quantity;
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
    if (clock == null) {
      return;
    }
    final boolean terminal =
        ordStatus == OrdStatus.Cancelled || ordStatus == OrdStatus.Filled
            || ordStatus == OrdStatus.Rejected;
    if (clOrdId == restingId) {
      filled += lastQuantity;
      if (terminal) {
        restingId = NONE;
      }
      return;
    }
    for (int i = 0; i < sweepIds.length; i++) {
      if (sweepIds[i] == clOrdId && clOrdId != NONE) {
        filled += lastQuantity;
        sweepCum[i] += lastQuantity;
        if (terminal) {
          sweepIds[i] = NONE;
          sweepLive--;
          if (sweepCum[i] < sweepQty[i]) {
            nextRetry = clock.nanoTime() + retryNanos;
          }
        }
        return;
      }
    }
  }

  @Override
  public void onCancelReject(final int venueId, final long clOrdId) {
    // the order had finished; its final report frees it
  }

  @Override
  public void onBookUpdate(final int venueId) {
    // the touch is read at poll
  }
}
