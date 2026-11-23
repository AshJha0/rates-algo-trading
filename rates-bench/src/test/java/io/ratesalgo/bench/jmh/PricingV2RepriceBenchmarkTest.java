package io.ratesalgo.bench.jmh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pricing v2 benchmark does what it says: each call is one complete round and one reprice.
 * It also prints a plain timing of the three cases, so that a build log carries a cost figure
 * even where JMH is not run; the JMH figure is the one to quote.
 */
class PricingV2RepriceBenchmarkTest {

  @Test
  void eachCallIsOneRoundAndOneReprice() {
    final PricingV2RepriceBenchmark b = new PricingV2RepriceBenchmark();
    b.setUp();
    final long start = b.reprices();
    final long first = b.roundThatMovesTheCurve();
    assertEquals(start + 1, b.reprices());
    final long second = b.roundThatMovesTheCurve();
    assertEquals(start + 2, b.reprices());
    assertNotEquals(first, second, "the quotes moved, so the 12-year fair value moved");
    assertEquals((double) second, (double) b.roundWithTheSameQuotes(), 2_000.0,
        "same quotes: only the residual drifts");
    assertEquals(start + 3, b.reprices());
    assertTrue(b.versionOneRound() > 0L);
  }

  @Test
  void plainTimingOfTheThreeCases() {
    final PricingV2RepriceBenchmark b = new PricingV2RepriceBenchmark();
    b.setUp();
    long sink = 0L;
    for (int k = 0; k < 20_000; k++) {
      sink += b.roundThatMovesTheCurve() + b.roundWithTheSameQuotes() + b.versionOneRound();
    }
    final int rounds = 20_000;
    final long t0 = System.nanoTime();
    for (int k = 0; k < rounds; k++) {
      sink += b.roundThatMovesTheCurve();
    }
    final long t1 = System.nanoTime();
    for (int k = 0; k < rounds; k++) {
      sink += b.roundWithTheSameQuotes();
    }
    final long t2 = System.nanoTime();
    for (int k = 0; k < rounds; k++) {
      sink += b.versionOneRound();
    }
    final long t3 = System.nanoTime();
    System.out.printf(
        "PRICING V2 plain timing, microseconds per round: moving %.1f, unchanged %.1f, version 1 %.1f"
            + " (checksum %d)%n",
        (t1 - t0) / 1e3 / rounds, (t2 - t1) / 1e3 / rounds, (t3 - t2) / 1e3 / rounds, sink);
    // a guard against a gross regression only: a round has cost tens of microseconds on every
    // machine it was run on; half a millisecond would be a loop stall worth failing for
    assertTrue((t1 - t0) / rounds < 500_000L, "a moving round took " + (t1 - t0) / rounds + " ns");
  }
}
