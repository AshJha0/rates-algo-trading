package io.ratesalgo.router.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The benchmark's machinery on a two-day variant with another seed: every strategy completes
 * its parents inside its limits, and the report renders. It checks no cost; the comparison is
 * {@link RouterBenchmarkTest}, run once on the fixed scenario.
 */
class RouterBenchmarkSmokeTest {

  static RouterBenchmarkScenario small() {
    final RouterBenchmarkScenario s = RouterBenchmarkScenario.ust10yImpactV1();
    return new RouterBenchmarkScenario(
        "smoke", s.market(), 2, 3, s.minLots(), s.maxLots(), 5_000_000_000L, s.maxParentNanos(),
        99L, s.urgency(), s.lambda(), s.toleranceTicks(), 1, 100);
  }

  @Test
  void everyStrategyCompletesInsideItsLimits() {
    final RouterBenchmarkScenario sc = small();
    final RouterBenchmarkResult r = RouterBenchmark.run(sc, RouterBenchmark.strategies(sc));
    assertEquals(8, r.strategies());
    for (int s = 0; s < r.strategies(); s++) {
      assertEquals(1.0, r.fillRate(s), 1e-12, r.name(s));
      assertEquals(0, r.violations(s), r.name(s));
      assertEquals(6, r.ticks(s).length);
      assertTrue(r.meanSeconds(s) >= 0.0);
    }
    assertTrue(r.maxTicksPastTouch(0) <= 2);
    assertEquals(0, r.selfCrosses(0));
    final String md = r.markdown("cmd", "abc");
    assertTrue(md.contains("| router (ladder model) |"));
    assertTrue(md.contains("(a) sweep on arrival"));
    assertTrue(md.contains("Reproduce with"));
    assertTrue(RouterBenchmarkTest.intro(sc).contains("not a market finding"));
  }

  @Test
  void scenarioIsValidated() {
    final RouterBenchmarkScenario s = RouterBenchmarkScenario.ust10yImpactV1();
    assertThrows(
        IllegalArgumentException.class,
        () -> new RouterBenchmarkScenario(
            "bad", s.market(), 1, 1, 1, 1, 0, 1, 0, 0.5, 0.5, 0.25, 1, 100));
    assertThrows(
        IllegalArgumentException.class,
        () -> SweepBaseline.onArrival("x", new int[] {1}, new double[0], 1, 1, 1));
  }
}
