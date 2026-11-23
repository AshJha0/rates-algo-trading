package io.ratesalgo.router.backtest;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.router.sim.SimulatedMarket;
import org.agrona.collections.Long2LongHashMap;

/**
 * Real alternatives to the router (docs/research/ROUTER_BENCHMARK.md): the parent, or each slice
 * of a fixed time schedule, is swept best all-in price first (price plus taker fee) across the
 * given venues' displayed levels, each venue's immediate-or-cancel child limited at the last level
 * it needs. What does not fill waits for the next slice, or after the last slice is swept again
 * after the retry delay.
 *
 * <ul>
 *   <li>{@link #onArrival}: the whole parent at once across every venue;
 *   <li>{@link #schedule} on one venue: the router's nominal schedule, on the single best venue;
 *   <li>{@link #schedule} on every venue: TWAP across venues.
 * </ul>
 */
public final class SweepBaseline implements ExecutionStrategy {

  private static final long NONE = -1L;

  private final String name;
  private final int[] venueIds;
  private final double[] takerFees;
  private final int slices;
  private final long intervalNanos;
  private final long retryNanos;
  private final long lot;
  private final int maxLevels;
  private final Long2LongHashMap orderedOf = new Long2LongHashMap(64, 0.65f, NONE);
  private final Long2LongHashMap cumOf = new Long2LongHashMap(64, 0.65f, NONE);
  private final long[] planQty;
  private final long[] planLimit;

  private SimulatedMarket market;
  private Clock clock;
  private long parentId = NONE;
  private Side side = Side.Buy;
  private long quantity;
  private long filled;
  private long outstanding;
  private int slicesLeft;
  private long nextSlice;
  private long nextRetry;
  private long nextId = 1;

  private SweepBaseline(
      final String name, final int[] venueIds, final double[] takerFees, final int slices,
      final long intervalNanos, final long retryNanos, final long lot, final int maxLevels) {
    if (venueIds.length != takerFees.length || venueIds.length == 0 || slices < 1 || maxLevels < 1) {
      throw new IllegalArgumentException("invalid sweep baseline " + name);
    }
    this.name = name;
    this.venueIds = venueIds.clone();
    this.takerFees = takerFees.clone();
    this.slices = slices;
    this.intervalNanos = intervalNanos;
    this.retryNanos = retryNanos;
    this.lot = lot;
    this.maxLevels = maxLevels;
    this.planQty = new long[venueIds.length];
    this.planLimit = new long[venueIds.length];
  }

  /** The whole parent swept at once across the venues, retried after {@code retryNanos}. */
  public static SweepBaseline onArrival(
      final String name, final int[] venueIds, final double[] takerFees, final long retryNanos,
      final long lot, final int maxLevels) {
    return new SweepBaseline(name, venueIds, takerFees, 1, 0L, retryNanos, lot, maxLevels);
  }

  /** {@code slices} equal slices {@code intervalNanos} apart, each swept across the venues. */
  public static SweepBaseline schedule(
      final String name, final int[] venueIds, final double[] takerFees, final int slices,
      final long intervalNanos, final long retryNanos, final long lot, final int maxLevels) {
    return new SweepBaseline(
        name, venueIds, takerFees, slices, intervalNanos, retryNanos, lot, maxLevels);
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
    orderedOf.clear();
    cumOf.clear();
  }

  @Override
  public void submit(final long id, final Side parentSide, final long qty) {
    parentId = id;
    side = parentSide;
    quantity = qty;
    filled = 0;
    outstanding = 0;
    slicesLeft = slices;
    nextSlice = clock.nanoTime();
    nextRetry = Long.MAX_VALUE;
    poll();
  }

  @Override
  public void poll() {
    if (clock == null || parentId == NONE || filled >= quantity) {
      return;
    }
    final long now = clock.nanoTime();
    final long unscheduled = quantity - filled - outstanding;
    if (slicesLeft > 0 && now >= nextSlice) {
      long size = slicesLeft == 1 ? unscheduled : unscheduled / slicesLeft;
      size -= size % lot;
      if (size <= 0) {
        size = Math.min(lot, unscheduled);
      }
      slicesLeft--;
      nextSlice += intervalNanos;
      send(size);
    } else if (slicesLeft == 0 && unscheduled > 0 && now >= nextRetry) {
      nextRetry = Long.MAX_VALUE;
      send(unscheduled);
    }
  }

  private void send(final long size) {
    if (size <= 0) {
      return;
    }
    final int k = plan(market, venueIds, takerFees, side, size, lot, maxLevels, planQty, planLimit);
    if (k == 0) {
      nextRetry = clock.nanoTime() + retryNanos;
      return;
    }
    for (int i = 0; i < venueIds.length; i++) {
      if (planQty[i] > 0) {
        final long id = nextId++;
        orderedOf.put(id, planQty[i]);
        cumOf.put(id, 0L);
        outstanding += planQty[i];
        market.newOrder(
            venueIds[i], id, parentId, 1, side, OrdType.Limit, TimeInForce.ImmediateOrCancel,
            planLimit[i], planQty[i]);
      }
    }
  }

  /**
   * Plans a sweep of {@code qty}: every displayed level of every venue up to {@code maxLevels},
   * cheapest all-in price first (ties to the earlier venue), each venue's take rounded to the lot.
   *
   * @return the number of venues with a child
   */
  static int plan(
      final SimulatedMarket m, final int[] venueIds, final double[] fees, final Side side,
      final long qty, final long lot, final int maxLevels, final long[] outQty,
      final long[] outLimit) {
    final boolean buy = side == Side.Buy;
    final Side opposite = buy ? Side.Sell : Side.Buy;
    final int nv = venueIds.length;
    final int cap = nv * maxLevels;
    final long[] price = new long[cap];
    final long[] size = new long[cap];
    final int[] venue = new int[cap];
    final double[] cost = new double[cap];
    int count = 0;
    for (int i = 0; i < nv; i++) {
      outQty[i] = 0;
      outLimit[i] = 0;
      for (int k = 0; k < maxLevels; k++) {
        final long px = m.levelPrice(venueIds[i], opposite, k);
        if (px <= 0) {
          break;
        }
        price[count] = px;
        size[count] = m.depth(venueIds[i], opposite, k);
        venue[count] = i;
        cost[count] = (buy ? px : -px) / 1e9 + fees[i];
        count++;
      }
    }
    final Integer[] order = new Integer[count];
    for (int i = 0; i < count; i++) {
      order[i] = i;
    }
    java.util.Arrays.sort(
        order,
        (a, b) -> cost[a] != cost[b] ? Double.compare(cost[a], cost[b]) : Integer.compare(venue[a], venue[b]));
    long remaining = qty;
    int venues = 0;
    for (int x = 0; x < count && remaining > 0; x++) {
      final int e = order[x];
      long take = Math.min(size[e], remaining);
      take -= take % lot;
      if (take <= 0) {
        continue;
      }
      if (outQty[venue[e]] == 0) {
        venues++;
      }
      outQty[venue[e]] += take;
      outLimit[venue[e]] = price[e];
      remaining -= take;
    }
    return venues;
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
    final long ordered = orderedOf.get(clOrdId);
    if (ordered == NONE || clock == null) {
      return;
    }
    filled += lastQuantity;
    outstanding -= lastQuantity;
    cumOf.put(clOrdId, cumOf.get(clOrdId) + lastQuantity);
    if (ordStatus == OrdStatus.Cancelled || ordStatus == OrdStatus.Rejected) {
      outstanding -= ordered - cumOf.get(clOrdId);
      nextRetry = clock.nanoTime() + retryNanos;
    }
    if (ordStatus == OrdStatus.Cancelled || ordStatus == OrdStatus.Filled
        || ordStatus == OrdStatus.Rejected) {
      orderedOf.remove(clOrdId);
      cumOf.remove(clOrdId);
    }
  }

  @Override
  public void onCancelReject(final int venueId, final long clOrdId) {
    // never cancels
  }

  @Override
  public void onBookUpdate(final int venueId) {
    // sweeps read the book when they send
  }
}
