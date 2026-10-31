package io.ratesalgo.router.backtest;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.router.sim.SimMarket;
import org.agrona.collections.Long2LongHashMap;

/**
 * The two baselines of API_SPEC.md Section 15.6: the whole parent sent at once as
 * immediate-or-cancel orders, either to one venue or split evenly by lots across venues. The limit
 * is the far touch plus a collar; any remainder is resent after the retry delay, so a baseline
 * eventually completes the way a desk crossing the spread would.
 */
public final class AggressiveBaseline implements ExecutionStrategy {

  private static final long NONE = -1L;

  private final String name;
  private final int[] venueIds;
  private final int collarTicks;
  private final long retryNanos;
  private final long lot;
  private final long tick;
  private final long[] pending;
  private final Long2LongHashMap venueOf = new Long2LongHashMap(64, 0.65f, NONE);
  private final Long2LongHashMap orderedOf = new Long2LongHashMap(64, 0.65f, NONE);

  private SimMarket market;
  private Clock clock;
  private long parentId = NONE;
  private Side side = Side.Buy;
  private long quantity;
  private long filled;
  private long nextRetry;
  private long nextId = 1;

  private AggressiveBaseline(
      final String name,
      final int[] venueIds,
      final int collarTicks,
      final long retryNanos,
      final long lot,
      final long tick) {
    this.name = name;
    this.venueIds = venueIds.clone();
    this.collarTicks = collarTicks;
    this.retryNanos = retryNanos;
    this.lot = lot;
    this.tick = tick;
    this.pending = new long[venueIds.length];
  }

  /** Everything to one venue. */
  public static AggressiveBaseline singleVenue(
      final int venueId, final int collarTicks, final long retryNanos, final long lot,
      final long tick) {
    return new AggressiveBaseline(
        "single-venue aggressive", new int[] {venueId}, collarTicks, retryNanos, lot, tick);
  }

  /** Equal lots to every venue at once. */
  public static AggressiveBaseline evenSplit(
      final int[] venueIds, final int collarTicks, final long retryNanos, final long lot,
      final long tick) {
    return new AggressiveBaseline(
        "even split aggressive", venueIds, collarTicks, retryNanos, lot, tick);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public void startDay(final SimMarket simMarket, final Clock simClock, final long seed) {
    this.market = simMarket;
    this.clock = simClock;
    this.parentId = NONE;
    venueOf.clear();
    orderedOf.clear();
  }

  @Override
  public void submit(final long id, final Side parentSide, final long qty) {
    parentId = id;
    side = parentSide;
    quantity = qty;
    filled = 0;
    final long lots = qty / lot;
    final int n = venueIds.length;
    for (int i = 0; i < n; i++) {
      pending[i] = (lots / n + (i < lots % n ? 1 : 0)) * lot;
    }
    sendPending();
  }

  private void sendPending() {
    for (int i = 0; i < venueIds.length; i++) {
      if (pending[i] <= 0) {
        continue;
      }
      final int v = venueIds[i];
      final long price =
          side == Side.Buy
              ? market.bestAsk(v) + collarTicks * tick
              : market.bestBid(v) - collarTicks * tick;
      final long id = nextId++;
      venueOf.put(id, i);
      orderedOf.put(id, pending[i]);
      market.newOrder(
          v, id, parentId, 1, side, OrdType.Limit, TimeInForce.ImmediateOrCancel, price,
          pending[i]);
      pending[i] = 0;
    }
  }

  @Override
  public void poll() {
    if (clock.nanoTime() < nextRetry) {
      return;
    }
    for (final long p : pending) {
      if (p > 0) {
        sendPending();
        return;
      }
    }
  }

  @Override
  public boolean isDone(final long id) {
    return id != parentId || filled == quantity;
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
    final long i = venueOf.get(clOrdId);
    if (i == NONE) {
      return;
    }
    filled += lastQuantity;
    if (ordStatus == OrdStatus.Cancelled) {
      pending[(int) i] += orderedOf.get(clOrdId) - cumQuantity;
      nextRetry = clock.nanoTime() + retryNanos;
    }
    if (ordStatus == OrdStatus.Cancelled || ordStatus == OrdStatus.Filled) {
      venueOf.remove(clOrdId);
      orderedOf.remove(clOrdId);
    }
  }

  @Override
  public void onCancelReject(final int venueId, final long clOrdId) {
    // baselines never cancel
  }

  @Override
  public void onBookUpdate(final int venueId) {
    // baselines do not watch the book
  }
}
