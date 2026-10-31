package io.ratesalgo.router.sim;

/**
 * One in-memory CLOB venue.
 *
 * @param venueId venue identifier
 * @param spreadTicks quoted spread in ticks (the bid is shared across venues; the ask is bid plus
 *     spread)
 * @param topDepthLots resting depth of other participants at the touch, lots
 * @param depthStepLots extra depth per level away from the touch, lots
 * @param arrivalLotsPerSecond market orders of one lot arriving per second on each side
 * @param latencyNanos one-way latency for our orders and cancels
 * @param takerFee fee for removing liquidity, price points per unit
 * @param makerFee fee for providing liquidity, price points per unit
 */
public record SimVenueSpec(
    int venueId,
    int spreadTicks,
    double topDepthLots,
    double depthStepLots,
    double arrivalLotsPerSecond,
    long latencyNanos,
    double takerFee,
    double makerFee) {

  /** Validates the specification. */
  public SimVenueSpec {
    if (venueId <= 0
        || spreadTicks < 1
        || !(topDepthLots >= 1.0)
        || !(depthStepLots >= 0.0)
        || !(arrivalLotsPerSecond > 0.0)
        || latencyNanos < 0) {
      throw new IllegalArgumentException(
          "invalid venue spec: id "
              + venueId
              + " spread "
              + spreadTicks
              + " depth "
              + topDepthLots
              + " step "
              + depthStepLots
              + " rate "
              + arrivalLotsPerSecond
              + " latency "
              + latencyNanos);
    }
  }
}
