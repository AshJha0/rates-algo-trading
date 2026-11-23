package io.ratesalgo.router.sim;

import io.ratesalgo.messages.sbe.VenueKind;
import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.VenueParams;
import java.util.List;

/**
 * Parameters of an {@link ImpactMarket}: the venues, the efficient price and the impact every
 * aggressive lot has, ours and other participants' alike.
 *
 * @param venues the venues, 1 to 8
 * @param levels price levels kept per side per venue
 * @param tickMantissa price increment, mantissa with exponent -9
 * @param lotSize one lot, minor units
 * @param startMidTicks starting efficient price, ticks
 * @param sigmaTicksPerSqrtSecond volatility of the efficient price, ticks per root second
 * @param permanentTicksPerLot permanent move of the efficient price per lot taken aggressively,
 *     by anyone; the rest of an order's impact is the depleted depth, which refills, so the
 *     book's resilience is below 100%
 * @param stepNanos simulation step
 */
public record ImpactConfig(
    List<ImpactVenueSpec> venues,
    int levels,
    long tickMantissa,
    long lotSize,
    double startMidTicks,
    double sigmaTicksPerSqrtSecond,
    double permanentTicksPerLot,
    long stepNanos) {

  /** Validates and copies. */
  public ImpactConfig {
    venues = List.copyOf(venues);
    if (venues.isEmpty() || venues.size() > 8 || levels < 2 || levels > 64) {
      throw new IllegalArgumentException("1 to 8 venues and 2 to 64 levels");
    }
    if (tickMantissa <= 0
        || lotSize <= 0
        || !(startMidTicks > 100.0)
        || !(sigmaTicksPerSqrtSecond >= 0.0)
        || !(permanentTicksPerLot >= 0.0)
        || stepNanos <= 0) {
      throw new IllegalArgumentException("invalid impact market parameters");
    }
  }

  /** The venues (an unmodifiable copy). */
  @Override
  public List<ImpactVenueSpec> venues() {
    return List.copyOf(venues);
  }

  /** Venue {@code i}. */
  public ImpactVenueSpec venue(final int i) {
    return venues.get(i);
  }

  /** Number of venues. */
  public int venueCount() {
    return venues.size();
  }

  /**
   * What a desk would declare about these venues without seeing inside the simulator: published
   * fees, lot and tick, measured one-way latency, the clob preset's daily volume and the declared
   * cancellation-rate prior {@link SimConfig#DECLARED_CANCEL_RATE}.
   */
  public VenueParams[] declaredVenues(final double participationCap, final int depthLevels) {
    final VenueParams[] out = new VenueParams[venues.size()];
    for (int i = 0; i < out.length; i++) {
      final ImpactVenueSpec v = venues.get(i);
      out[i] =
          new VenueParams(
              v.venueId(), VenueKind.Clob, tickMantissa, lotSize, lotSize, v.takerFee(),
              v.makerFee(), v.latencyNanos(), SimConfig.DECLARED_CANCEL_RATE,
              RouterPresets.clob(v.venueId()).adv(), participationCap, depthLevels);
    }
    return out;
  }
}
