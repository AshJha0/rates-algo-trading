package io.ratesalgo.app.loop;

import io.ratesalgo.api.HedgeOrderSink;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.core.HotPath;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.SmartOrderRouter;
import org.agrona.collections.Long2LongHashMap;

/**
 * P3's {@link HedgeOrderSink} over P4's routers (DECISIONS.md DR-R2W-07): one {@link
 * SmartOrderRouter} per hedge instrument, because a router's {@code BookView} describes one
 * instrument across venues.
 *
 * <p>The hedger states urgency as a time ({@code urgencyNanos}, 0 for now); the router takes a
 * number in [0, 1] that shortens its schedule. The mapping is {@code 1 - (urgencyNanos - now) /
 * horizon} clipped to [0, 1], with {@code horizon} the router's full schedule length, so a
 * deadline one horizon away is fully patient and 0 is fully urgent. A parent the router cannot
 * take (unknown instrument, a quantity off the lot grid, no free parent slot) is refused with
 * false, never an exception, so the hedger releases its reservation. Allocation-free after
 * warm-up.
 */
@HotPath
public final class RouterHedgeSink implements HedgeOrderSink {

  private final SmartOrderRouter[] routers;
  private final int[] instruments;
  private final Clock clock;
  private final double horizonNanos;
  private final long[] increment;
  private final Long2LongHashMap routerOfParent = new Long2LongHashMap(64, 0.6f, -1L);
  private long accepted;
  private long refused;

  RouterHedgeSink(
      final SmartOrderRouter[] routers, final int[] instruments, final Clock clock, final long horizonNanos) {
    this.routers = routers;
    this.instruments = instruments;
    this.clock = clock;
    this.horizonNanos = horizonNanos;
    this.increment = new long[routers.length];
    for (int j = 0; j < routers.length; j++) {
      long lot = 0;
      for (final io.ratesalgo.router.VenueParams v : routers[j].venues()) {
        lot = Math.max(lot, v.lotSize());
      }
      increment[j] = lot;
    }
  }

  private int routerFor(final int instrumentId) {
    for (int j = 0; j < instruments.length; j++) {
      if (instruments[j] == instrumentId) {
        return j;
      }
    }
    return -1;
  }

  /** The router urgency for a hedger deadline. */
  double urgency(final long urgencyNanos) {
    if (urgencyNanos == 0L) {
      return 1.0;
    }
    final double u = 1.0 - (urgencyNanos - clock.epochNanos()) / horizonNanos;
    return Math.max(0.0, Math.min(1.0, u));
  }

  @Override
  public boolean submit(
      final long parentId,
      final int instrumentId,
      final Side side,
      final long quantityMinor,
      final long limitPrice,
      final long urgencyNanos,
      final long sentNanos) {
    final int j = routerFor(instrumentId);
    if (j < 0 || quantityMinor <= 0 || limitPrice < 0 || routerOfParent.containsKey(parentId)) {
      refused++;
      return false;
    }
    final SmartOrderRouter router = routers[j];
    if (quantityMinor % increment[j] != 0 || router.parentsLive() >= router.maxParents()) {
      refused++;
      return false;
    }
    router.submit(parentId, instrumentId, side, quantityMinor, urgency(urgencyNanos), limitPrice);
    routerOfParent.put(parentId, j);
    accepted++;
    return true;
  }

  @Override
  public void cancel(final long parentId) {
    final long j = routerOfParent.remove(parentId);
    if (j >= 0) {
      routers[(int) j].cancel(parentId);
    }
  }

  @Override
  public void cancelAll() {
    for (final SmartOrderRouter router : routers) {
      router.cancelAll();
    }
    routerOfParent.clear();
  }

  /** Forgets a parent the router has finished. */
  void done(final long parentId) {
    routerOfParent.remove(parentId);
  }

  /** Parents passed to a router. */
  public long accepted() {
    return accepted;
  }

  /** Parents refused before reaching a router. */
  public long refused() {
    return refused;
  }
}
