package io.ratesalgo.venues.d2c;

import io.ratesalgo.venues.sim.Json;
import io.ratesalgo.venues.sim.Presets;
import io.ratesalgo.venues.sim.Px;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * D2C simulator parameters, read from a preset in {@code presets.json}.
 *
 * @param responseWindowNanos time dealers have to respond before the client decides
 * @param minDealers fewest dealers in competition
 * @param maxDealers most dealers in competition
 * @param dealerResponseProbability chance a simulated dealer answers
 * @param dealerDelayMedianNanos median simulated dealer response time
 * @param dealerDelaySigma log-space sigma of the response time
 * @param bondHalfSpreadMedian median half spread for bonds, price mantissa
 * @param swapHalfSpreadMedian median half spread for swaps, rate mantissa
 * @param halfSpreadSigma log-space sigma of half spreads
 * @param toleranceMedian client tolerance as a multiple of the median half spread
 * @param toleranceSigma log-space sigma of the tolerance
 * @param doneAwayProbability chance the client trades elsewhere
 * @param informedFraction share of RFQs from informed clients
 * @param informedJumpMultiple post-trade mid jump, in median half spreads, after an informed RFQ
 * @param informedLagNanos delay of that jump after the trade
 * @param markoutHorizonNanos markout horizon
 * @param bondVolPerSqrtHour mid volatility for bonds, price mantissa per square-root hour
 * @param swapVolPerSqrtHour mid volatility for swaps, rate mantissa per square-root hour
 * @param zone time zone of the arrival schedule
 * @param basePerHour baseline RFQ arrival rate
 * @param events intensity bumps around fixings and releases
 * @param bondSizes bond size preset
 * @param swapSizes swap size preset
 */
public record D2cConfig(
    long responseWindowNanos,
    int minDealers,
    int maxDealers,
    double dealerResponseProbability,
    long dealerDelayMedianNanos,
    double dealerDelaySigma,
    long bondHalfSpreadMedian,
    long swapHalfSpreadMedian,
    double halfSpreadSigma,
    double toleranceMedian,
    double toleranceSigma,
    double doneAwayProbability,
    double informedFraction,
    double informedJumpMultiple,
    long informedLagNanos,
    long markoutHorizonNanos,
    long bondVolPerSqrtHour,
    long swapVolPerSqrtHour,
    ZoneId zone,
    double basePerHour,
    List<ArrivalEvent> events,
    SizePreset bondSizes,
    SizePreset swapSizes) {

  /** An intensity bump: Gaussian in time of day around {@code time}. */
  public record ArrivalEvent(LocalTime time, String label, double amplitudePerHour, double widthMinutes) {}

  /** Normalises the list. */
  public D2cConfig {
    events = List.copyOf(events);
    if (minDealers < 1 || maxDealers < minDealers) {
      throw new IllegalArgumentException("dealer range " + minDealers + ".." + maxDealers);
    }
  }

  private static final long MS = 1_000_000L;

  /** Loads a named preset. */
  public static D2cConfig preset(final String name) {
    final Map<String, Object> p = Presets.get("d2c", name);
    final Map<String, Object> spread = Json.obj(p, "halfSpread");
    final Map<String, Object> arrivals = Json.obj(p, "arrivals");
    final List<ArrivalEvent> events = new ArrayList<>();
    for (final Object e : Json.list(arrivals, "events")) {
      events.add(
          new ArrivalEvent(
              LocalTime.parse(Json.str(e, "time")),
              Json.str(e, "label"),
              Json.num(e, "amplitudePerHour").doubleValue(),
              Json.num(e, "widthMinutes").doubleValue()));
    }
    return new D2cConfig(
        Json.num(p, "responseWindowMillis").longValue() * MS,
        Json.num(p, "minDealers").intValue(),
        Json.num(p, "maxDealers").intValue(),
        Json.num(p, "dealerResponseProbability").doubleValue(),
        Json.num(Json.obj(p, "dealerDelayMillis"), "median").longValue() * MS,
        Json.num(Json.obj(p, "dealerDelayMillis"), "sigma").doubleValue(),
        Px.price(Json.str(Json.obj(spread, "bond"), "median")),
        Px.price(Json.str(Json.obj(spread, "swap"), "median")),
        Json.num(Json.obj(spread, "bond"), "sigma").doubleValue(),
        Json.num(Json.obj(p, "clientToleranceMultiple"), "median").doubleValue(),
        Json.num(Json.obj(p, "clientToleranceMultiple"), "sigma").doubleValue(),
        Json.num(p, "doneAwayProbability").doubleValue(),
        Json.num(p, "informedFraction").doubleValue(),
        Json.num(p, "informedJumpMultiple").doubleValue(),
        Json.num(p, "informedLagMillis").longValue() * MS,
        Json.num(p, "markoutHorizonMillis").longValue() * MS,
        Px.price(Json.str(Json.obj(p, "midVolPerSqrtHour"), "bond")),
        Px.price(Json.str(Json.obj(p, "midVolPerSqrtHour"), "swap")),
        ZoneId.of(Json.str(p, "timeZone")),
        Json.num(arrivals, "basePerHour").doubleValue(),
        events,
        SizePreset.from(Json.list(Json.obj(p, "sizes"), "bond")),
        SizePreset.from(Json.list(Json.obj(p, "sizes"), "swap")));
  }

  /** The default preset. */
  public static D2cConfig defaults() {
    return preset("default");
  }

  /** Arrival intensity in RFQs per hour at a local time of day. */
  public double intensityPerHour(final LocalTime t) {
    double lambda = basePerHour;
    final double minutes = t.toSecondOfDay() / 60.0;
    for (final ArrivalEvent e : events) {
      final double d = minutes - e.time().toSecondOfDay() / 60.0;
      lambda += e.amplitudePerHour() * Math.exp(-d * d / (2 * e.widthMinutes() * e.widthMinutes()));
    }
    return lambda;
  }

  /** Upper bound of the intensity, for thinning. */
  public double maxIntensityPerHour() {
    double max = basePerHour;
    for (final ArrivalEvent e : events) {
      max += e.amplitudePerHour();
    }
    return max;
  }
}
