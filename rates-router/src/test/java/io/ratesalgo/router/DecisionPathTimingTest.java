package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.backtest.RouterBenchmarkScenario;
import io.ratesalgo.router.sim.ImpactConfig;
import io.ratesalgo.router.sim.ImpactMarket;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Times the decision path: re-evaluating a real journaled decision with the touch model and with
 * the ladder model, the work {@code SmartOrderRouter.decide} does after reading the book. Printed,
 * not asserted (a shared machine's timings are not a pass mark); a stand-in for the JMH benchmark
 * until rates-bench depends on rates-router. Single thread, median of nine batches.
 */
class DecisionPathTimingTest {

  private static final int BATCH = 50_000;

  /** Takes the first ladder decision with a sweep on every row from a router on the benchmark market. */
  private static CostTable[] decisions() {
    final ManualClock clock = new ManualClock();
    final ImpactConfig cfg = RouterBenchmarkScenario.ust10yImpactV1().market();
    final ImpactMarket market = new ImpactMarket(cfg, 5L, clock);
    final VenueParams[] venues = cfg.declaredVenues(0.25, 5);
    final CostTable[] out = new CostTable[2];
    final SmartOrderRouter ladder =
        new SmartOrderRouter(RouterPresets.ladder(), venues, market, market, FrameSink.NONE, clock,
            RandomSource.seeded(1L), 4, 64, 1L, RouterTimeouts.DEFAULT, ExecutionModel.defaults());
    final SmartOrderRouter touch =
        new SmartOrderRouter(RouterPresets.global(), venues, market, market, FrameSink.NONE, clock,
            RandomSource.seeded(1L), 4, 64, 1_000_000L);
    for (int i = 0; i < 300; i++) {
      clock.advance(cfg.stepNanos());
      market.step();
      market.drain(ladder);
    }
    ladder.submit(1, 10, Side.Buy, 40 * RouterPresets.ONE_MILLION, 1.0, 0L);
    out[0] = new CostTable();
    out[0].copyFrom(ladder.lastDecision());
    touch.submit(2, 10, Side.Buy, 40 * RouterPresets.ONE_MILLION, 1.0, 0L);
    out[1] = new CostTable();
    out[1].copyFrom(touch.lastDecision());
    return out;
  }

  private static double nanosPerOp(final Runnable op) {
    final double[] batches = new double[9];
    for (int w = 0; w < 4 * BATCH; w++) {
      op.run();
    }
    for (int b = 0; b < batches.length; b++) {
      final long t0 = System.nanoTime();
      for (int i = 0; i < BATCH; i++) {
        op.run();
      }
      batches[b] = (System.nanoTime() - t0) / (double) BATCH;
    }
    Arrays.sort(batches);
    return batches[batches.length / 2];
  }

  @Test
  void timeTheDecisionPath() {
    final CostTable[] d = decisions();
    final VenueParams[] venues = RouterBenchmarkScenario.ust10yImpactV1().market().declaredVenues(0.25, 5);
    final Allocator allocator = new Allocator();
    final CostTable work = new CostTable();
    final ExecutionModel model = ExecutionModel.defaults();
    final RouterParams ladderParams = RouterPresets.ladder();
    final RouterParams touchParams = RouterPresets.global();
    final double ladder =
        nanosPerOp(() -> {
          work.copyFrom(d[0]);
          work.clearOutputs();
          allocator.allocate(work, ladderParams, venues, model);
        });
    assertTrue(work.contentEquals(d[0]), "the timed re-evaluation reproduces the decision");
    final double touch =
        nanosPerOp(() -> {
          work.copyFrom(d[1]);
          work.clearOutputs();
          allocator.allocate(work, touchParams, venues);
        });
    final double copy = nanosPerOp(() -> work.copyFrom(d[0]));
    System.out.println(
        String.format(Locale.ROOT,
            "DecisionPathTimingTest: ladder decision %.0f ns, touch decision %.0f ns, table copy %.0f ns"
                + " (median of 9 batches of %d, %s %s, %d cpus; a shared machine)",
            ladder, touch, copy, BATCH, System.getProperty("java.vm.name"),
            System.getProperty("java.version"), Runtime.getRuntime().availableProcessors()));
    assertTrue(ladder > 0 && touch > 0);
  }
}
