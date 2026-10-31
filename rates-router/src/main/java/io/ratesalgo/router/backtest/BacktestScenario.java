package io.ratesalgo.router.backtest;

import io.ratesalgo.router.sim.SimConfig;
import java.util.Objects;

/**
 * A seeded synthetic back-test: the market, the parent orders and the statistics.
 *
 * @param name scenario name used in reports
 * @param market the simulated venues
 * @param days number of synthetic days
 * @param parentsPerDay parent orders per day, worked one after another
 * @param minLots smallest parent, lots
 * @param maxLots largest parent, lots
 * @param gapNanos idle time before each parent
 * @param maxParentNanos a parent not complete after this fails the run
 * @param seed base seed; day {@code d} uses {@code seed + d}
 * @param urgency urgency the router gives every parent
 * @param blockLength bootstrap block length, days
 * @param resamples bootstrap resamples
 */
public record BacktestScenario(
    String name,
    SimConfig market,
    int days,
    int parentsPerDay,
    long minLots,
    long maxLots,
    long gapNanos,
    long maxParentNanos,
    long seed,
    double urgency,
    int blockLength,
    int resamples) {

  /** Validates the scenario. */
  public BacktestScenario {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(market, "market");
    if (days < 2
        || parentsPerDay < 1
        || minLots < 1
        || maxLots < minLots
        || gapNanos < 0
        || maxParentNanos <= 0
        || blockLength < 1
        || blockLength > days
        || resamples < 100) {
      throw new IllegalArgumentException("invalid scenario " + name);
    }
  }

  /**
   * The shipped scenario {@code ust10y_three_venue_v1}: 40 days of 15 parents each, 10 to 60
   * lots of 1 million, random side, 30 seconds apart, urgency 0.5, block length 5 days, 2000
   * resamples.
   */
  public static BacktestScenario ust10yThreeVenuesV1() {
    return new BacktestScenario(
        "ust10y_three_venue_v1",
        SimConfig.ust10yThreeVenues(),
        40,
        15,
        10,
        60,
        30_000_000_000L,
        900_000_000_000L,
        20_261_031L,
        0.5,
        5,
        2_000);
  }
}
