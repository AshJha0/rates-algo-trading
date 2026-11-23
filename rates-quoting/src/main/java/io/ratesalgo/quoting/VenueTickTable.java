package io.ratesalgo.quoting;

import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.hedge.VenueTicks;
import org.agrona.collections.Long2LongHashMap;

/**
 * Venue price increments (review 2026-11-23, Q8): a tick per product type with per-instrument
 * overrides, as Decimal9 mantissas in the instrument's quote convention. The simulator's venues
 * use 1/128 of a point for every Treasury on the CLOB and 0.0001 percent of rate for swaps
 * (rates-venues presets.json), which {@link #simulatorDefaults} sets. Reads are allocation-free.
 */
public final class VenueTickTable implements VenueTicks {

  /** 1/128 of a point, the simulator's CLOB tick for every Treasury. */
  public static final long TREASURY_128THS = 7_812_500L;

  /** 0.0001 percent of rate (0.01 bp), the simulator's SEF tick for swaps. */
  public static final long SWAP_RATE_TICK = 100_000L;

  private final long[] byType = new long[ProductType.values().length];
  private final Long2LongHashMap byInstrument = new Long2LongHashMap(64, 0.5f, 0L);

  /** The simulator's ticks: 1/128 for bonds and futures, 0.0001 percent for swaps. */
  public static VenueTickTable simulatorDefaults() {
    return new VenueTickTable()
        .type(ProductType.BOND, TREASURY_128THS)
        .type(ProductType.FUTURE, TREASURY_128THS)
        .type(ProductType.SWAP, SWAP_RATE_TICK);
  }

  /** Sets the tick of every instrument of {@code type}; 0 clears it. */
  public VenueTickTable type(final ProductType type, final long tickMantissa) {
    byType[type.index()] = nonNegative(tickMantissa);
    return this;
  }

  /** Sets one instrument's tick, overriding its type's; 0 falls back to the type's. */
  public VenueTickTable instrument(final int instrumentId, final long tickMantissa) {
    if (nonNegative(tickMantissa) == 0L) {
      byInstrument.remove(instrumentId);
    } else {
      byInstrument.put(instrumentId, tickMantissa);
    }
    return this;
  }

  @Override
  public long tick(final Instrument instrument) {
    final long own = byInstrument.get(instrument.id());
    return own != 0L ? own : byType[instrument.type().index()];
  }

  /**
   * {@code price} rounded to a multiple of {@code tick} in the dealer's favour: down for a price
   * at which we buy, up for one at which we sell. A tick of 0 or less leaves the price as it is.
   */
  public static long roundForDealer(final long price, final long tick, final boolean weBuy) {
    if (tick <= 0L) {
      return price;
    }
    final long below = price - Math.floorMod(price, tick);
    return weBuy || below == price ? below : below + tick;
  }

  private static long nonNegative(final long tick) {
    if (tick < 0L) {
      throw new IllegalArgumentException("tick " + tick);
    }
    return tick;
  }
}
