package io.ratesalgo.router.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The paired statistics of the router benchmark against values worked by hand and by tables. */
class PairedStatsTest {

  @Test
  void meanSdAndRiskAdjusted() {
    final double[] x = {1, 2, 3, 4};
    assertEquals(2.5, PairedStats.mean(x));
    assertEquals(Math.sqrt(5.0 / 3.0), PairedStats.sd(x), 1e-15);
    assertEquals(2.5 + 0.5 * Math.sqrt(5.0 / 3.0), PairedStats.riskAdjusted(x, 0.5), 1e-15);
    assertEquals(0.0, PairedStats.sd(new double[] {7}));
  }

  @Test
  void studentQuantilesMatchTables() {
    assertEquals(2.228, PairedStats.studentQuantile(0.975, 10), 1e-3);
    assertEquals(1.960, PairedStats.studentQuantile(0.975, 1e6), 1e-3);
    assertEquals(-2.228, PairedStats.studentQuantile(0.025, 10), 1e-3);
    assertEquals(0.0, PairedStats.studentQuantile(0.5, 3));
    assertEquals(0.05, PairedStats.studentTwoSidedP(2.228, 10), 1e-3);
    assertEquals(0.0, PairedStats.studentTwoSidedP(Double.POSITIVE_INFINITY, 10));
    assertThrows(IllegalArgumentException.class, () -> PairedStats.studentQuantile(1.0, 3));
  }

  @Test
  void pairedTOfAKnownDifference() {
    final double[] a = {3, 5, 4, 6, 7};
    final double[] b = {1, 2, 2, 3, 4};
    // differences 2, 3, 2, 3, 3: mean 2.6, sd 0.5477, se 0.2449, t 10.61 on 4 degrees of freedom
    final double[] t = PairedStats.pairedT(a, b, 0.95);
    assertEquals(2.6, t[0], 1e-12);
    assertEquals(0.24495, t[1], 1e-5);
    assertEquals(10.614, t[2], 1e-3);
    assertTrue(t[3] < 0.001);
    assertEquals(2.6 - 2.776 * 0.24495, t[4], 1e-3);
    assertEquals(2.6 + 2.776 * 0.24495, t[5], 1e-3);
    final double[] same = PairedStats.pairedT(b, b, 0.95);
    assertEquals(0.0, same[0]);
    assertEquals(1.0, same[3]);
    assertThrows(IllegalArgumentException.class, () -> PairedStats.pairedT(a, new double[] {1}, 0.95));
  }

  @Test
  void blockBootstrapIsSeededAndCoversTheDifference() {
    final int n = 60;
    final double[] a = new double[n];
    final double[] b = new double[n];
    final int[] day = new int[n];
    for (int i = 0; i < n; i++) {
      day[i] = i / 3;
      b[i] = (i * 7919 % 13) / 13.0;
      a[i] = b[i] + 1.0 + ((i * 31) % 5 - 2) * 0.1;
    }
    final double[] x = PairedStats.pairedBlockBootstrap(a, b, day, 0.0, 2, 500, 0.95, 3L);
    final double[] y = PairedStats.pairedBlockBootstrap(a, b, day, 0.0, 2, 500, 0.95, 3L);
    assertArrayEquals(x, y);
    assertTrue(x[0] < 1.0 && x[1] > 0.9, x[0] + " " + x[1]);
    final double[] ra = PairedStats.pairedBlockBootstrap(a, b, day, 1.0, 2, 500, 0.95, 3L);
    assertTrue(ra[0] <= ra[1]);
    assertThrows(IllegalArgumentException.class,
        () -> PairedStats.pairedBlockBootstrap(a, b, day, 0.0, 99, 500, 0.95, 3L));
  }

  @Test
  void logGammaAndTheIncompleteBeta() {
    assertEquals(Math.log(24.0), PairedStats.logGamma(5.0), 1e-12);
    assertEquals(0.5 * Math.log(Math.PI), PairedStats.logGamma(0.5), 1e-12);
    assertEquals(0.0, PairedStats.regularizedIncompleteBeta(0.0, 2, 3));
    assertEquals(1.0, PairedStats.regularizedIncompleteBeta(1.0, 2, 3));
    assertEquals(0.5, PairedStats.regularizedIncompleteBeta(0.5, 4, 4), 1e-12);
  }
}
