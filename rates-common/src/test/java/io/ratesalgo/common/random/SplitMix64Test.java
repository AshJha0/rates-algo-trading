package io.ratesalgo.common.random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class SplitMix64Test {

  /** The published first outputs for seed 0 (Steele, Lea and Flood's reference implementation). */
  @Test
  void firstOutputsForSeedZeroAreTheReferenceValues() {
    final SplitMix64 rng = new SplitMix64(0L);
    assertEquals(0xE220A8397B1DCDAFL, rng.nextLong());
    assertEquals(0x6E789E6AA1B965F4L, rng.nextLong());
    assertEquals(0x06C45D188009454FL, rng.nextLong());
  }

  @Test
  void streamsOfOneSeedDifferAndRepeat() {
    assertEquals(SplitMix64.forStream(7L, 3L).nextLong(), SplitMix64.forStream(7L, 3L).nextLong());
    assertNotEquals(SplitMix64.forStream(7L, 3L).nextLong(), SplitMix64.forStream(7L, 4L).nextLong());
    assertNotEquals(SplitMix64.forStream(7L, 3L).nextLong(), SplitMix64.forStream(8L, 3L).nextLong());
  }

  @Property
  void nextIntStaysBelowTheBound(@ForAll final long seed, @ForAll @IntRange(min = 1, max = 1_000_000) final int bound) {
    final SplitMix64 rng = new SplitMix64(seed);
    for (int i = 0; i < 20; i++) {
      final int v = rng.nextInt(bound);
      assertTrue(v >= 0 && v < bound);
      final double u = rng.nextDouble();
      assertTrue(u >= 0.0 && u < 1.0);
    }
  }

  @Test
  void nextIntRefusesANonPositiveBound() {
    assertThrows(IllegalArgumentException.class, () -> new SplitMix64(1L).nextInt(0));
  }

  /**
   * 200,000 draws: the mean of a standard normal has standard error 0.0022 and its variance 0.0032,
   * the mean of an exponential with mean 2 has standard error 0.0045. The bounds are about 4.5
   * standard errors.
   */
  @Test
  void gaussianAndExponentialHaveTheirMoments() {
    final SplitMix64 rng = new SplitMix64(42L);
    final int n = 200_000;
    double sum = 0;
    double squares = 0;
    double exponential = 0;
    for (int i = 0; i < n; i++) {
      final double z = rng.nextGaussian();
      sum += z;
      squares += z * z;
      exponential += rng.nextExponential(2.0);
    }
    assertEquals(0.0, sum / n, 0.01);
    assertEquals(1.0, squares / n, 0.015);
    assertEquals(2.0, exponential / n, 0.02);
  }
}
