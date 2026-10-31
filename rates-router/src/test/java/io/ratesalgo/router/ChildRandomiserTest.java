package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Anti-gaming: child sizes and intervals from the seeded randomiser stay within their bounds and
 * pass two simple predictability checks, the lag-k autocorrelation (lags 1 to 10, each within
 * {@code 3 / sqrt(n)}, about the 99.7 percent band under independence) and the Wald-Wolfowitz
 * runs test about the median ({@code |z| < 3}). A deterministic sawtooth fails both, which shows
 * the checks have teeth.
 */
class ChildRandomiserTest {

  private static final int N = 2_000;
  private static final long LOT = 100L;

  static double autocorrelation(final double[] x, final int lag) {
    final double mean = Arrays.stream(x).average().orElseThrow();
    double num = 0.0;
    double den = 0.0;
    for (int i = 0; i < x.length; i++) {
      den += (x[i] - mean) * (x[i] - mean);
      if (i + lag < x.length) {
        num += (x[i] - mean) * (x[i + lag] - mean);
      }
    }
    return num / den;
  }

  static double runsZ(final double[] x) {
    final double[] sorted = x.clone();
    Arrays.sort(sorted);
    final double median = sorted[x.length / 2];
    int above = 0;
    int below = 0;
    int runs = 0;
    int last = 0;
    for (final double v : x) {
      if (v == median) {
        continue;
      }
      final int sign = v > median ? 1 : -1;
      if (sign > 0) {
        above++;
      } else {
        below++;
      }
      if (sign != last) {
        runs++;
        last = sign;
      }
    }
    final double n1 = above;
    final double n2 = below;
    final double expected = 2.0 * n1 * n2 / (n1 + n2) + 1.0;
    final double variance =
        2.0 * n1 * n2 * (2.0 * n1 * n2 - n1 - n2) / ((n1 + n2) * (n1 + n2) * (n1 + n2 - 1.0));
    return (runs - expected) / Math.sqrt(variance);
  }

  static boolean unpredictable(final double[] x) {
    final double band = 3.0 / Math.sqrt(x.length);
    for (int lag = 1; lag <= 10; lag++) {
      if (Math.abs(autocorrelation(x, lag)) >= band) {
        return false;
      }
    }
    return Math.abs(runsZ(x)) < 3.0;
  }

  @Test
  void sizesAndIntervalsAreBoundedAndPassPredictabilityChecks() {
    final ChildRandomiser r = new ChildRandomiser(RandomSource.seeded(42L), 0.25, 0.3);
    final long base = 40 * LOT;
    final long interval = 7_500_000_000L;
    final double[] sizes = new double[N];
    final double[] gaps = new double[N];
    for (int i = 0; i < N; i++) {
      final long q = r.jitteredSize(base, LOT, Long.MAX_VALUE);
      final long g = r.interval(interval);
      assertTrue(q >= base * 0.75 - LOT / 2.0 && q <= base * 1.25 + LOT / 2.0, "size " + q);
      assertEquals(0, q % LOT);
      assertTrue(g >= interval * 0.7 - 1 && g <= interval * 1.3 + 1, "interval " + g);
      sizes[i] = q;
      gaps[i] = g;
    }
    assertTrue(unpredictable(sizes), "sizes failed the predictability checks");
    assertTrue(unpredictable(gaps), "intervals failed the predictability checks");
    final long distinct = Arrays.stream(sizes).distinct().count();
    assertTrue(distinct >= 15, "too few distinct sizes: " + distinct);
  }

  @Test
  void aSawtoothFailsTheChecks() {
    final double[] saw = new double[N];
    for (int i = 0; i < N; i++) {
      saw[i] = 30 + i % 20;
    }
    assertFalse(unpredictable(saw));
  }

  @Test
  void slicesCoverTheRemainderExactly() {
    final ChildRandomiser r = new ChildRandomiser(RandomSource.seeded(7L), 0.25, 0.3);
    long remaining = 57 * LOT;
    int slices = 5;
    long total = 0;
    while (slices > 0) {
      final long q = r.sliceSize(remaining, slices, LOT);
      assertTrue(q >= LOT && q <= remaining);
      total += q;
      remaining -= q;
      slices--;
    }
    assertEquals(57 * LOT, total);
    assertEquals(LOT, r.sliceSize(LOT, 3, LOT));
    assertThrows(IllegalArgumentException.class, () -> new ChildRandomiser(() -> 0.5, 0.6, 0.1));
    assertThrows(IllegalArgumentException.class, () -> new ChildRandomiser(() -> 0.5, 0.1, -0.1));
  }
}
