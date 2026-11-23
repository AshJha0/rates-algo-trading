package io.ratesalgo.router.sim;

/**
 * One venue of an {@link ImpactMarket}. Every number is a declared assumption of the synthetic
 * market; none is given to the router.
 *
 * @param venueId venue identifier
 * @param spreadTicks quoted spread when the book is full, ticks
 * @param topDepthLots resting depth of other participants at the touch when full, lots
 * @param depthStepLots extra depth per level away from the touch, lots
 * @param marketOrdersPerSecond other participants' market orders arriving on each side
 * @param meanOrderLots mean size of one of their market orders (1 plus a geometric), lots
 * @param cancelRatePerSecond rate at which each resting lot of theirs cancels
 * @param refillPerSecond extra rate at which a depleted level fills back towards its base
 * @param latencyNanos one-way latency of our orders and cancels
 * @param takerFee fee for removing liquidity, price points per unit
 * @param makerFee fee for providing liquidity, price points per unit
 * @param rejectProbability chance that one of our orders is rejected on arrival
 */
public record ImpactVenueSpec(
    int venueId,
    int spreadTicks,
    double topDepthLots,
    double depthStepLots,
    double marketOrdersPerSecond,
    double meanOrderLots,
    double cancelRatePerSecond,
    double refillPerSecond,
    long latencyNanos,
    double takerFee,
    double makerFee,
    double rejectProbability) {

  /** Validates the specification. */
  public ImpactVenueSpec {
    if (venueId <= 0
        || spreadTicks < 1
        || !(topDepthLots >= 1.0)
        || !(depthStepLots >= 0.0)
        || !(marketOrdersPerSecond >= 0.0)
        || !(meanOrderLots >= 1.0)
        || !(cancelRatePerSecond >= 0.0)
        || !(refillPerSecond >= 0.0)
        || latencyNanos < 0
        || !(rejectProbability >= 0.0 && rejectProbability < 1.0)) {
      throw new IllegalArgumentException("invalid impact venue spec for venue " + venueId);
    }
  }
}
