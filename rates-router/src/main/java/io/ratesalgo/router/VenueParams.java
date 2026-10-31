package io.ratesalgo.router;

import static io.ratesalgo.router.RouterParams.check;
import static io.ratesalgo.router.RouterParams.in;

import io.ratesalgo.messages.sbe.VenueKind;
import java.util.Objects;

/**
 * Static description of one venue for one instrument: the fee schedule, size rules, latency and
 * the liquidity parameters the cost model needs that the book does not show. Units are those of
 * API_SPEC.md Section 0: prices and fees in price points (fees per unit of quantity), quantities
 * in minor units of face, times in nanoseconds.
 *
 * @param venueId venue identifier, as on NewOrder and ExecutionReport
 * @param kind central limit order book or futures venue
 * @param tickMantissa price increment, int64 mantissa with exponent -9
 * @param lotSize quantity increment
 * @param minSize smallest child order the venue accepts, a multiple of {@code lotSize}
 * @param takerFee fee for removing liquidity, price points per unit of quantity
 * @param makerFee fee for providing liquidity, price points per unit; negative is a rebate
 * @param latencyNanos one-way order latency to the venue
 * @param cancelRatePerSecond rate at which each unit queued ahead of us cancels
 * @param adv average daily volume on the venue, quantity
 * @param participationCap largest fraction of visible plus expected liquidity one decision takes
 * @param depthLevels number of opposite levels counted as visible liquidity
 */
public record VenueParams(
    int venueId,
    VenueKind kind,
    long tickMantissa,
    long lotSize,
    long minSize,
    double takerFee,
    double makerFee,
    long latencyNanos,
    double cancelRatePerSecond,
    long adv,
    double participationCap,
    int depthLevels) {

  /** Validates every field against its pinned range. */
  public VenueParams {
    Objects.requireNonNull(kind, "kind");
    check(venueId > 0, "venueId must be > 0", venueId);
    check(tickMantissa > 0, "tickMantissa must be > 0", tickMantissa);
    check(lotSize > 0, "lotSize must be > 0", lotSize);
    check(minSize >= lotSize && minSize % lotSize == 0, "minSize a multiple of lotSize", minSize);
    check(in(takerFee, 0.0, 1.0), "takerFee in [0, 1] price points", takerFee);
    check(in(makerFee, -1.0, 1.0), "makerFee in [-1, 1] price points", makerFee);
    check(latencyNanos >= 0 && latencyNanos <= 1_000_000_000L, "latency in [0, 1 s]", latencyNanos);
    check(in(cancelRatePerSecond, 0.0, 10.0), "cancelRatePerSecond in [0, 10]", cancelRatePerSecond);
    check(adv > 0, "adv must be > 0", adv);
    check(participationCap > 0.0 && participationCap <= 1.0, "participationCap", participationCap);
    check(depthLevels >= 1 && depthLevels <= 20, "depthLevels in [1, 20]", depthLevels);
  }
}
