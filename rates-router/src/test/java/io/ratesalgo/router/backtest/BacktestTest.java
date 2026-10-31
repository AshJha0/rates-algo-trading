package io.ratesalgo.router.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.sim.SimConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The P4 gate on execution quality: in the shipped scenario the router beats single-venue
 * aggressive and the even split by at least {@link #MARGIN_BPS} on the lower end of the 95
 * percent block-bootstrap interval of the mean daily saving. The result is printed so CI logs
 * carry the numbers; API_SPEC.md Section 15.6 quotes them with the scenario assumptions.
 */
class BacktestTest {

  /** Required lower bound of the saving interval against each baseline, bps of price. */
  static final double MARGIN_BPS = 1.0;

  static List<ExecutionStrategy> strategies(final SimConfig market, final double urgency) {
    final long retry = RouterPresets.global().retryDelayNanos();
    return List.of(
        new RouterStrategy(RouterPresets.global(), market.routerVenues(0.25, 5), urgency, 10),
        AggressiveBaseline.singleVenue(1, 8, retry, market.lotSize(), market.tickMantissa()),
        AggressiveBaseline.evenSplit(
            new int[] {1, 2, 3}, 8, retry, market.lotSize(), market.tickMantissa()));
  }

  @Test
  void routerBeatsBothBaselinesInTheShippedScenario() {
    final BacktestScenario scenario = BacktestScenario.ust10yThreeVenuesV1();
    final BacktestResult result =
        BacktestHarness.run(scenario, strategies(scenario.market(), scenario.urgency()));
    System.out.print(result.format());
    assertEquals(3, result.strategies());
    assertEquals(scenario.days(), result.dayCosts(0).length);
    for (int s = 1; s < result.strategies(); s++) {
      final double[] ci = result.savingInterval(s);
      assertTrue(
          ci[0] > MARGIN_BPS,
          "router vs " + result.name(s) + ": interval " + ci[0] + " to " + ci[1]);
    }
  }

  @Test
  void bootstrapIsSeededAndValidated() {
    final double[] x = {1, 2, 3, 4, 5, 6, 7, 8};
    final double[] a = BlockBootstrap.meanInterval(x, 2, 500, 0.95, 1L);
    final double[] b = BlockBootstrap.meanInterval(x, 2, 500, 0.95, 1L);
    assertEquals(a[0], b[0]);
    assertTrue(a[0] < 4.5 && a[1] > 4.5);
    assertThrows(IllegalArgumentException.class, () -> BlockBootstrap.meanInterval(x, 9, 500, 0.95, 1L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BacktestScenario(
                "bad", SimConfig.ust10yTwoVenues(), 1, 1, 1, 1, 0, 1, 0, 0.5, 1, 100));
  }
}
