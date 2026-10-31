package io.ratesalgo.router.sim;

import io.ratesalgo.messages.sbe.VenueKind;
import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.VenueParams;
import java.util.List;

/**
 * Parameters of a set of in-memory venues trading one instrument. Every number is a declared
 * assumption of the synthetic market, listed with the back-test result it produces
 * (API_SPEC.md Section 15.6).
 */
public final class SimConfig {

  private final SimVenueSpec[] venues;
  private final int levels;
  private final double cancelRatePerSecond;
  private final double refillPerSecond;
  private final long tickMantissa;
  private final long lotSize;
  private final long startBidTicks;
  private final double moveRatePerSecond;
  private final double permanentTicksPerLot;
  private final long stepNanos;
  private final double tradingSecondsPerDay;

  /**
   * @param venues the venues
   * @param levels price levels per side
   * @param cancelRatePerSecond cancellation rate of each lot queued ahead of ours
   * @param refillPerSecond rate at which depleted depth returns to its base
   * @param tickMantissa price increment, mantissa with exponent -9
   * @param lotSize quantity of one lot, minor units
   * @param startBidTicks starting best bid, in ticks
   * @param moveRatePerSecond rate of one-tick moves of the common price, each direction
   * @param permanentTicksPerLot permanent price move per lot we take aggressively
   * @param stepNanos simulation step
   * @param tradingSecondsPerDay used to express venue volume per day
   */
  public SimConfig(
      final List<SimVenueSpec> venues,
      final int levels,
      final double cancelRatePerSecond,
      final double refillPerSecond,
      final long tickMantissa,
      final long lotSize,
      final long startBidTicks,
      final double moveRatePerSecond,
      final double permanentTicksPerLot,
      final long stepNanos,
      final double tradingSecondsPerDay) {
    if (venues.isEmpty() || venues.size() > 8 || levels < 1 || levels > 64) {
      throw new IllegalArgumentException(
          "1 to 8 venues and 1 to 64 levels: " + venues.size() + ", " + levels);
    }
    if (!(cancelRatePerSecond >= 0.0)
        || !(refillPerSecond >= 0.0)
        || tickMantissa <= 0
        || lotSize <= 0
        || startBidTicks <= 100
        || !(moveRatePerSecond >= 0.0)
        || !(permanentTicksPerLot >= 0.0)
        || stepNanos <= 0
        || moveRatePerSecond * stepNanos / 1e9 > 0.5) {
      throw new IllegalArgumentException("invalid simulator parameters");
    }
    this.venues = venues.toArray(new SimVenueSpec[0]);
    this.levels = levels;
    this.cancelRatePerSecond = cancelRatePerSecond;
    this.refillPerSecond = refillPerSecond;
    this.tickMantissa = tickMantissa;
    this.lotSize = lotSize;
    this.startBidTicks = startBidTicks;
    this.moveRatePerSecond = moveRatePerSecond;
    this.permanentTicksPerLot = permanentTicksPerLot;
    this.stepNanos = stepNanos;
    this.tradingSecondsPerDay = tradingSecondsPerDay;
  }

  /**
   * The shipped back-test scenario {@code ust10y_three_venue_v1}: a 10-year Treasury on three
   * cash CLOBs, ticks of 1/64, lots of 1 million, a primary venue with thicker depth and the most
   * flow, a secondary with half of each, and a two-tick venue with thin depth and no maker fee.
   */
  public static SimConfig ust10yThreeVenues() {
    return new SimConfig(
        List.of(
            new SimVenueSpec(1, 1, 12.0, 6.0, 1.0, 250_000L, 0.0005, 0.0002),
            new SimVenueSpec(2, 1, 6.0, 3.0, 0.5, 400_000L, 0.0004, 0.0001),
            new SimVenueSpec(3, 2, 4.0, 2.0, 0.3, 600_000L, 0.0003, 0.0)),
        10,
        0.05,
        0.2,
        RouterPresets.TICK_64TH,
        RouterPresets.ONE_MILLION,
        6_304L,
        0.0219,
        0.02,
        100_000_000L,
        23_400.0);
  }

  /** The first two venues of {@link #ust10yThreeVenues()}, used by COOKBOOK recipe 17. */
  public static SimConfig ust10yTwoVenues() {
    final SimConfig three = ust10yThreeVenues();
    return new SimConfig(
        List.of(three.venues[0], three.venues[1]),
        three.levels,
        three.cancelRatePerSecond,
        three.refillPerSecond,
        three.tickMantissa,
        three.lotSize,
        three.startBidTicks,
        three.moveRatePerSecond,
        three.permanentTicksPerLot,
        three.stepNanos,
        three.tradingSecondsPerDay);
  }

  /**
   * Router venue parameters consistent with this market: the same fees, latencies and lot, the
   * cancellation rate, and daily volume equal to both sides' arrival rate over the trading day.
   */
  public VenueParams[] routerVenues(final double participationCap, final int depthLevels) {
    final VenueParams[] out = new VenueParams[venues.length];
    for (int i = 0; i < venues.length; i++) {
      final SimVenueSpec v = venues[i];
      out[i] =
          new VenueParams(
              v.venueId(),
              VenueKind.Clob,
              tickMantissa,
              lotSize,
              lotSize,
              v.takerFee(),
              v.makerFee(),
              v.latencyNanos(),
              cancelRatePerSecond,
              Math.round(2.0 * v.arrivalLotsPerSecond() * tradingSecondsPerDay) * lotSize,
              participationCap,
              depthLevels);
    }
    return out;
  }

  /** Number of venues. */
  public int venueCount() {
    return venues.length;
  }

  /** Venue {@code i}. */
  public SimVenueSpec venue(final int i) {
    return venues[i];
  }

  /** Price levels per side. */
  public int levels() {
    return levels;
  }

  /** Cancellation rate per lot ahead per second. */
  public double cancelRatePerSecond() {
    return cancelRatePerSecond;
  }

  /** Depth refill rate per second. */
  public double refillPerSecond() {
    return refillPerSecond;
  }

  /** Tick, mantissa. */
  public long tickMantissa() {
    return tickMantissa;
  }

  /** Lot, minor units. */
  public long lotSize() {
    return lotSize;
  }

  /** Starting best bid in ticks. */
  public long startBidTicks() {
    return startBidTicks;
  }

  /** One-tick moves per second in each direction. */
  public double moveRatePerSecond() {
    return moveRatePerSecond;
  }

  /** Permanent impact, ticks per lot taken. */
  public double permanentTicksPerLot() {
    return permanentTicksPerLot;
  }

  /** Simulation step. */
  public long stepNanos() {
    return stepNanos;
  }

  /** Trading seconds per day. */
  public double tradingSecondsPerDay() {
    return tradingSecondsPerDay;
  }
}
