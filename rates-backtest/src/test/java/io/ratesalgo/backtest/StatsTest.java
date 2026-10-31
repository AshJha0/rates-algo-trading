package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.SplitMix64;
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
