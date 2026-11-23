package io.ratesalgo.router.backtest;

import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.sim.ImpactConfig;
import io.ratesalgo.router.sim.ImpactVenueSpec;
import java.util.List;
import java.util.Objects;

/**
 * The router benchmark's scenario (docs/research/ROUTER_BENCHMARK.md): the market, the parents,
 * and the statistics, fixed before any comparison was run.
 *
 * @param name scenario name used in reports
 * @param market the simulated venues
 * @param days number of synthetic days; a fresh market and fresh strategies each day
 * @param parentsPerDay parent orders per day, worked one after another
 * @param minLots smallest parent, lots
 * @param maxLots largest parent, lots
 * @param gapNanos idle time before each parent
 * @param maxParentNanos a parent not complete after this fails the run
 * @param seed base seed; day {@code d} uses {@code seed + d}
 * @param urgency urgency the router gives every parent
 * @param lambda the risk-adjusted cost is the mean plus lambda standard deviations
 * @param toleranceTicks how much worse, in ticks, the router's risk-adjusted cost may be than a
 *     real baseline's before the test fails
 * @param blockLength bootstrap block length, days
 * @param resamples bootstrap resamples
 */
public record RouterBenchmarkScenario(
    String name,
    ImpactConfig market,
    int days,
    int parentsPerDay,
    long minLots,
    long maxLots,
    long gapNanos,
    long maxParentNanos,
    long seed,
    double urgency,
    double lambda,
    double toleranceTicks,
    int blockLength,
    int resamples) {

  /** Validates the scenario. */
  public RouterBenchmarkScenario {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(market, "market");
    if (days < 2 || parentsPerDay < 1 || minLots < 1 || maxLots < minLots || gapNanos < 0
        || maxParentNanos <= 0 || !(lambda >= 0.0) || !(toleranceTicks >= 0.0) || blockLength < 1
        || blockLength > days || resamples < 100 || !(urgency >= 0.0 && urgency <= 1.0)) {
      throw new IllegalArgumentException("invalid scenario " + name);
    }
  }

  /**
   * {@code ust10y_impact_v1}, fixed on 2026-11-23 before the comparison was run (DR-R6-R05).
   *
   * <p>A 10-year note on three CLOBs, ticks of 1/64 and lots of 1 million. Venue 1: one-tick
   * spread, 30 lots at the touch and 10 more per level, 0.8 market orders a second each side of
   * mean 3 lots, each resting lot cancelling at 0.10 a second, refill 0.5 a second, 250
   * microseconds, fees 0.0005 and 0.0002. Venue 2: one tick, 15 + 6 per level, 0.4 orders of mean
   * 2, cancellation 0.15, refill 0.4, 400 microseconds, fees 0.0004 and 0.0001. Venue 3: two
   * ticks, 8 + 4 per level, 0.2 orders of mean 2, cancellation 0.25, refill 0.3, 1.5
   * milliseconds, fees 0.0003 and 0, and 2% of our orders rejected. Ten levels a side. The
   * efficient price moves 0.209 ticks per root second (0.5 points a day over 23,400 seconds) and
   * 0.02 ticks for every lot anyone takes (50 lots move it a tick for good); depth taken refills,
   * so resilience is below 100%. Steps of 100 ms.
   *
   * <p>Parents: 30 days of 10, 10 to 60 lots (the three touches hold 53), random side, 30 s
   * apart, urgency 0.5. Risk-adjusted cost is mean plus 0.5 standard deviations of the per-parent
   * shortfall; the test allows the router 0.25 ticks of risk-adjusted cost worse than a real
   * baseline. Bootstrap blocks of 3 days, 2,000 resamples.
   */
  public static RouterBenchmarkScenario ust10yImpactV1() {
    final ImpactConfig market =
        new ImpactConfig(
            List.of(
                new ImpactVenueSpec(1, 1, 30.0, 10.0, 0.8, 3.0, 0.10, 0.5, 250_000L, 0.0005, 0.0002, 0.0),
                new ImpactVenueSpec(2, 1, 15.0, 6.0, 0.4, 2.0, 0.15, 0.4, 400_000L, 0.0004, 0.0001, 0.0),
                new ImpactVenueSpec(3, 2, 8.0, 4.0, 0.2, 2.0, 0.25, 0.3, 1_500_000L, 0.0003, 0.0, 0.02)),
            10,
            RouterPresets.TICK_64TH,
            RouterPresets.ONE_MILLION,
            6_304.5,
            0.5 / (RouterPresets.TICK_64TH / 1e9) / Math.sqrt(23_400.0),
            0.02,
            100_000_000L);
    return new RouterBenchmarkScenario(
        "ust10y_impact_v1",
        market,
        30,
        10,
        10,
        60,
        30_000_000_000L,
        600_000_000_000L,
        20_261_123L,
        0.5,
        0.5,
        0.25,
        3,
        2_000);
  }
}
