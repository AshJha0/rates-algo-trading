package io.ratesalgo.app.sim;

import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;

/**
 * Resting liquidity on the CLOB simulator for a simulated day: a market maker (owner {@code LP},
 * not an engine session) that keeps {@link #LEVELS} bids and offers around each Treasury's D2C mid,
 * one tick apart, and refreshes them at a fixed interval. P2's CLOB starts empty; without this the
 * router would have nothing to trade against and the composite book no microprice. The sizes
 * alternate between the refreshes so the book feed always moves (DECISIONS.md DR-R2W-09).
 */
public final class ClobLiquidity {

  /** Levels per side. */
  public static final int LEVELS = 3;

  private static final long[] SIZES = {25_000_000L, 50_000_000L, 100_000_000L};

  private final UsdUniverse universe;
  private final D2cRfqSimulator reference;
  private final ClobSimulator clob;
  private final Clock clock;
  private final long intervalNanos;
  private final long[][] orders;
  private long nextNanos = Long.MIN_VALUE;
  private long refreshes;

  public ClobLiquidity(
      final UsdUniverse universe,
      final D2cRfqSimulator reference,
      final ClobSimulator clob,
      final Clock clock,
      final long intervalNanos) {
    this.universe = universe;
    this.reference = reference;
    this.clob = clob;
    this.clock = clock;
    this.intervalNanos = intervalNanos;
    this.orders = new long[universe.bonds().size()][2 * LEVELS];
  }

  /** Refreshes the quotes if due; returns the number of instruments refreshed. */
  public int doWork() {
    final long now = clock.nanoTime();
    if (nextNanos != Long.MIN_VALUE && now < nextNanos) {
      return 0;
    }
    nextNanos = now + intervalNanos;
    refreshes++;
    int n = 0;
    for (int j = 0; j < universe.bonds().size(); j++) {
      final UsdUniverse.BondSpec b = universe.bonds().get(j);
      final long tick = b.tick();
      final long mid = Math.round((double) reference.mid(b.securityId()) / tick) * tick;
      for (final long id : orders[j]) {
        if (id > 0) {
          clob.cancelInternal(id);
        }
      }
      for (int level = 0; level < LEVELS; level++) {
        final long size = SIZES[(level + (int) (refreshes & 1)) % SIZES.length];
        orders[j][2 * level] = clob.submitInternal("LP", b.instrumentId(), true, mid - (level + 1) * tick, size, size);
        orders[j][2 * level + 1] = clob.submitInternal("LP", b.instrumentId(), false, mid + (level + 1) * tick, size, size);
      }
      n++;
    }
    return n;
  }

  /** Refresh rounds done. */
  public long refreshes() {
    return refreshes;
  }
}
