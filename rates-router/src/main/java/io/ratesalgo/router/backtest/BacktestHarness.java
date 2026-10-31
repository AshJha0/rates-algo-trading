package io.ratesalgo.router.backtest;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.sim.SimMarket;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Runs strategies over the seeded synthetic days of a {@link BacktestScenario} and measures the
 * realised cost of each parent (API_SPEC.md Section 15.6).
 *
 * <p>Every strategy sees the same days: the same market seed, the same parent sides, sizes and
 * arrival gaps. The cost of a parent is its side times (average fill price minus the mid of the
 * first venue at arrival) plus fees per unit, in basis points of the arrival mid; the cost of a
 * day is the quantity-weighted mean over its parents. The comparison is paired by day.
 */
public final class BacktestHarness {

  private static final long START_EPOCH = 1_767_225_600_000_000_000L;
  private static final long DAY_NANOS = 86_400_000_000_000L;

  private BacktestHarness() {}

  /**
   * Runs the scenario.
   *
   * @param scenario the days and the market
   * @param strategies strategy 0 is compared with every other
   * @return daily costs per strategy
   * @throws IllegalStateException if a parent does not complete in time
   */
  public static BacktestResult run(
      final BacktestScenario scenario, final List<ExecutionStrategy> strategies) {
    final String[] names = new String[strategies.size()];
    final double[][] costs = new double[strategies.size()][scenario.days()];
    for (int s = 0; s < strategies.size(); s++) {
      final ExecutionStrategy strategy = strategies.get(s);
      names[s] = strategy.name();
      for (int d = 0; d < scenario.days(); d++) {
        costs[s][d] = runDay(scenario, strategy, d);
      }
    }
    return new BacktestResult(scenario, names, costs);
  }

  private static double runDay(
      final BacktestScenario sc, final ExecutionStrategy strategy, final int day) {
    final long seed = sc.seed() + day;
    final ManualClock clock = new ManualClock(START_EPOCH + day * DAY_NANOS);
    final SimMarket market = new SimMarket(sc.market(), seed, clock);
    strategy.startDay(market, clock, seed);
    final SplittableRandom parents = new SplittableRandom(seed ^ 0x9E3779B97F4A7C15L);
    final long step = sc.market().stepNanos();
    final long lot = sc.market().lotSize();
    double weighted = 0.0;
    double total = 0.0;
    for (int p = 0; p < sc.parentsPerDay(); p++) {
      final Side side = parents.nextBoolean() ? Side.Buy : Side.Sell;
      final long lots = sc.minLots() + parents.nextLong(sc.maxLots() - sc.minLots() + 1);
      for (long t = 0; t < sc.gapNanos(); t += step) {
        advance(clock, market, strategy, step);
      }
      final long parentId = day * 1_000L + p + 1;
      final double arrival = market.referenceMid() / 1e9;
      final long qty = lots * lot;
      strategy.submit(parentId, side, qty);
      long elapsed = 0;
      while (!strategy.isDone(parentId)) {
        if (elapsed > sc.maxParentNanos()) {
          throw new IllegalStateException(
              strategy.name()
                  + ": parent "
                  + parentId
                  + " incomplete after "
                  + elapsed
                  + " ns, filled "
                  + market.parentFilled(parentId)
                  + " of "
                  + qty);
        }
        advance(clock, market, strategy, step);
        elapsed += step;
      }
      final long filled = market.parentFilled(parentId);
      if (filled != qty) {
        throw new IllegalStateException(
            strategy.name() + ": parent " + parentId + " filled " + filled + " of " + qty);
      }
      final double average = market.parentNotional(parentId) / filled;
      final double fees = market.parentFees(parentId) / filled;
      final double sign = side == Side.Buy ? 1.0 : -1.0;
      final double bps = (sign * (average - arrival) + fees) / arrival * 1e4;
      weighted += bps * qty;
      total += qty;
    }
    return weighted / total;
  }

  private static void advance(
      final ManualClock clock,
      final SimMarket market,
      final ExecutionStrategy strategy,
      final long step) {
    clock.advance(step);
    market.step();
    market.drain(strategy);
    strategy.poll();
  }
}
