package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.common.random.SplitMix64;
import io.ratesalgo.backtest.util.Yaml;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Cross-checks the Java statistics against {@code data/fixtures/backtest/stats_crosscheck.json},
 * written by {@code ratesresearch.backtest_analysis.write_crosscheck}. The Python tests check the
 * same file, so the two implementations agree: bit for bit for the generator and the bootstrap,
 * to 1e-12 relative for Welch's p-value.
 */
class StatsTest {

  private static double[] doubles(final Object list) {
    final List<Object> l = Yaml.asList(list, "fixture");
    final double[] out = new double[l.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = Yaml.toDouble(l.get(i), "fixture");
    }
    return out;
  }

  @Test
  void matchesThePythonFixture() {
    final Map<String, Object> root =
        Yaml.loadMap(TestSupport.repoRoot().resolve("data/fixtures/backtest/stats_crosscheck.json"));
    final Map<String, Object> in = Yaml.asMap(root.get("input"), "input");
    final Map<String, Object> ex = Yaml.asMap(root.get("expected"), "expected");
    final double[] a = doubles(in.get("a"));
    final double[] b = doubles(in.get("b"));

    final Stats.Welch w = Stats.welch(a, b);
    final Map<String, Object> ew = Yaml.asMap(ex.get("welch"), "welch");
    assertEquals(Yaml.number(ew, "t", "w"), w.t(), 1e-12);
    assertEquals(Yaml.number(ew, "df", "w"), w.df(), 1e-10);
    assertEquals(Yaml.number(ew, "p", "w"), w.p(), 1e-12);

    final Stats.Interval ci =
        Stats.blockBootstrapMeanCi(
            a,
            (int) Yaml.integer(in, "block", "in"),
            (int) Yaml.integer(in, "resamples", "in"),
            Yaml.number(in, "level", "in"),
            Yaml.integer(in, "seed", "in"));
    final Map<String, Object> eb = Yaml.asMap(ex.get("bootstrap"), "bootstrap");
    assertEquals(Yaml.number(eb, "estimate", "b"), ci.estimate(), 0.0);
    assertEquals(Yaml.number(eb, "lower", "b"), ci.lower(), 0.0);
    assertEquals(Yaml.number(eb, "upper", "b"), ci.upper(), 0.0);

    assertArrayEquals(doubles(ex.get("holm")), Stats.holm(doubles(in.get("p"))), 1e-15);

    final SplitMix64 rng = new SplitMix64(Yaml.integer(in, "splitmix_seed", "in"));
    for (final Object hex : Yaml.asList(ex.get("splitmix_longs_hex"), "longs")) {
      assertEquals(HexFormat.fromHexDigitsToLong(hex.toString()), rng.nextLong());
    }
    for (final Object v : Yaml.asList(ex.get("splitmix_ints_bound_10"), "ints")) {
      assertEquals(((Number) v).intValue(), rng.nextInt(10));
    }
  }

  @Test
  void knownValuesAndEdges() {
    // Student t with 10 df: two-sided p of t = 2.228 is 0.05 to four figures
    assertEquals(0.05, Stats.studentTwoSidedP(2.228138852, 10), 1e-8);
    assertEquals(1.0, Stats.studentTwoSidedP(0.0, 5), 1e-15);
    assertEquals(Math.log(24.0), Stats.logGamma(5.0), 1e-13);
    assertEquals(Math.log(Math.sqrt(Math.PI)), Stats.logGamma(0.5), 1e-13);
    assertEquals(0.0, Stats.regularizedIncompleteBeta(0.0, 2, 3), 0.0);
    assertEquals(1.0, Stats.regularizedIncompleteBeta(1.0, 2, 3), 0.0);
    // I_x(1, 1) = x
    assertEquals(0.3, Stats.regularizedIncompleteBeta(0.3, 1, 1), 1e-14);
    assertEquals(2.0, Stats.quantile(new double[] {1, 2, 3}, 0.5), 0.0);
    assertEquals(1.5, Stats.quantile(new double[] {1, 2}, 0.5), 0.0);
    assertThrows(IllegalArgumentException.class, () -> Stats.mean(new double[0]));
    assertThrows(IllegalArgumentException.class, () -> Stats.variance(new double[] {1}));
    assertThrows(IllegalArgumentException.class, () -> Stats.welch(new double[] {1, 1}, new double[] {2, 2}));
    assertThrows(IllegalArgumentException.class, () -> Stats.holm(new double[] {1.5}));
    assertThrows(IllegalArgumentException.class, () -> Stats.blockBootstrapMeanCi(new double[] {1, 2}, 3, 10, 0.9, 1));
    assertThrows(IllegalArgumentException.class, () -> Stats.quantile(new double[] {1}, 2));
    assertThrows(IllegalArgumentException.class, () -> Stats.logGamma(0));
    assertThrows(IllegalArgumentException.class, () -> Stats.studentTwoSidedP(1, 0));
    assertThrows(IllegalArgumentException.class, () -> Stats.regularizedIncompleteBeta(2, 1, 1));
    assertTrue(Stats.logGamma(0.25) > 0);
  }

  /**
   * Known values: differences 1..5 have mean 3 and standard error sqrt(2.5 / 5), so t = 3 *
   * sqrt(2) on 4 degrees of freedom; scipy.stats.ttest_rel gives p = 0.01323559956368269.
   */
  @Test
  void pairedTestMatchesKnownValues() {
    final double[] b = {10, 20, 30, 40, 50};
    final double[] a = {11, 22, 33, 44, 55};
    final Stats.Paired r = Stats.pairedT(a, b);
    assertEquals(5, r.n());
    assertEquals(3.0, r.meanDifference(), 1e-15);
    assertEquals(Math.sqrt(0.5), r.standardError(), 1e-15);
    assertEquals(3 * Math.sqrt(2), r.t(), 1e-14);
    assertEquals(4.0, r.df(), 0.0);
    assertEquals(0.01323559956368269, r.p(), 1e-13);
    assertEquals(-r.t(), Stats.pairedT(b, a).t(), 1e-15);
    assertEquals(r.p(), Stats.pairedT(b, a).p(), 1e-15);
    assertArrayEquals(new double[] {1, 2, 3, 4, 5}, Stats.pairedDifferences(a, b), 0.0);
    final Stats.Interval ci = Stats.pairedBlockBootstrapCi(a, b, 2, 500, 0.9, 7);
    final Stats.Interval direct = Stats.blockBootstrapMeanCi(new double[] {1, 2, 3, 4, 5}, 2, 500, 0.9, 7);
    assertEquals(direct, ci);
    assertTrue(ci.lower() <= 3.0 && 3.0 <= ci.upper() && ci.lower() >= 1.0 && ci.upper() <= 5.0);
    assertThrows(IllegalArgumentException.class, () -> Stats.pairedT(new double[] {1, 2}, new double[] {1}));
    assertThrows(IllegalArgumentException.class, () -> Stats.pairedT(new double[] {2, 3}, new double[] {1, 2}));
    assertThrows(IllegalArgumentException.class, () -> Stats.pairedT(new double[] {2}, new double[] {1}));
  }

  /**
   * Why the paired test is the method for a common-random-number design: the same sessions, a
   * steady gain of about 30 on a session spread in the thousands. Welch cannot see it; the paired
   * test can.
   */
  @Test
  void pairedTestFindsWhatWelchMissesOnSharedSessions() {
    final SplitMix64 rng = new SplitMix64(42);
    final double[] base = new double[20];
    final double[] treat = new double[20];
    for (int i = 0; i < base.length; i++) {
      base[i] = 5_000 * rng.nextGaussian();
      treat[i] = base[i] + 30 + 10 * rng.nextGaussian();
    }
    assertTrue(Stats.welch(treat, base).p() > 0.9);
    assertTrue(Stats.pairedT(treat, base).p() < 1e-6);
  }

  @Test
  void benjaminiHochbergMatchesKnownValues() {
    // sorted 0.005, 0.01, 0.03, 0.04 times 4/1, 4/2, 4/3, 4/4 = 0.02, 0.02, 0.04, 0.04
    assertArrayEquals(new double[] {0.02, 0.04, 0.04, 0.02},
        Stats.benjaminiHochberg(new double[] {0.01, 0.04, 0.03, 0.005}), 1e-15);
    // the running minimum: 0.04 * 3/2 = 0.06 is capped by the larger rank's 0.05
    assertArrayEquals(new double[] {0.03, 0.05, 0.05}, Stats.benjaminiHochberg(new double[] {0.01, 0.04, 0.05}), 1e-15);
    assertArrayEquals(new double[] {1.0, 1.0}, Stats.benjaminiHochberg(new double[] {1.0, 0.9}), 0.0);
    assertEquals(0, Stats.benjaminiHochberg(new double[0]).length);
    final double[] p = {0.001, 0.2, 0.03, 0.04, 0.5, 0.012};
    final double[] bh = Stats.benjaminiHochberg(p);
    final double[] holm = Stats.holm(p);
    for (int i = 0; i < p.length; i++) {
      assertTrue(p[i] <= bh[i] && bh[i] <= holm[i], "raw <= BH <= Holm at " + i);
    }
    assertThrows(IllegalArgumentException.class, () -> Stats.benjaminiHochberg(new double[] {-0.1}));
  }

  @Test
  void generatorAndJson() {
    final SplitMix64 r = new SplitMix64(1);
    double sum = 0;
    for (int i = 0; i < 10_000; i++) {
      sum += r.nextGaussian();
      assertTrue(r.nextExponential(1.0) >= 0);
    }
    assertTrue(Math.abs(sum / 10_000) < 0.05);
    assertThrows(IllegalArgumentException.class, () -> r.nextInt(0));
    assertEquals(
        "{\"a\":1.0,\"b\":[1,\"x\\n\",true,null],\"c\":1.5E+17,\"d\":1.0E-7,\"e\":15000000.0}",
        Json.write(Map.of("b", java.util.Arrays.asList(1L, "x\n", true, null), "a", 1.0, "c", 1.5e17, "d", 1e-7, "e", 1.5e7)));
    assertThrows(IllegalArgumentException.class, () -> Json.write(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
    assertEquals(1.5e7, Yaml.toDouble(Yaml.parse("1.5E+7"), "x"), 0.0);
    assertEquals("2026-02-02", Yaml.parse("2026-02-02"));
    assertEquals("\"\\u0001\\t\\r\\\\\\\"\"", Json.write("\u0001\t\r\\\""));
  }
}
