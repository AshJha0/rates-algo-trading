package io.ratesalgo.router;

import static io.ratesalgo.router.RouterParams.check;
import static io.ratesalgo.router.RouterParams.in;

/**
 * What one venue accepts and how hard the router may use it, for the ladder model.
 *
 * <p>An aggressive child goes immediate-or-cancel where the venue takes it, fill-or-kill where it
 * takes only that (the sweep sizes the child to displayed depth, so a fill-or-kill that does not
 * fill means the depth was not there), and otherwise as a day order that the router cancels as
 * soon as the venue acknowledges it. A venue's minimum size is {@link VenueParams#minSize}: no
 * child below it is sent.
 *
 * @param venueId venue identifier
 * @param immediateOrCancel the venue accepts IOC
 * @param fillOrKill the venue accepts FOK
 * @param maxOrdersPerSecond token-bucket refill rate of new child orders
 * @param maxBurst token-bucket depth: new orders that may go at once
 * @param maxOrderToTrade orders per fill (both decayed) above which the venue is throttled
 * @param minOrdersForRatio the order-to-trade limit applies only after this many decayed orders
 */
public record VenueCapabilities(
    int venueId,
    boolean immediateOrCancel,
    boolean fillOrKill,
    double maxOrdersPerSecond,
    int maxBurst,
    double maxOrderToTrade,
    int minOrdersForRatio) {

  /** Validates every field. */
  public VenueCapabilities {
    check(venueId > 0, "venueId must be > 0", venueId);
    check(in(maxOrdersPerSecond, 0.01, 10_000.0), "maxOrdersPerSecond in [0.01, 10000]", maxOrdersPerSecond);
    check(maxBurst >= 1 && maxBurst <= 10_000, "maxBurst in [1, 10000]", maxBurst);
    check(in(maxOrderToTrade, 1.0, 1e6), "maxOrderToTrade in [1, 1e6]", maxOrderToTrade);
    check(minOrdersForRatio >= 0, "minOrdersForRatio >= 0", minOrdersForRatio);
  }

  /** IOC and FOK, 50 orders a second in bursts of 20, at most 50 orders per fill after 50 orders. */
  public static VenueCapabilities standard(final int venueId) {
    return new VenueCapabilities(venueId, true, true, 50.0, 20, 50.0, 50);
  }
}
