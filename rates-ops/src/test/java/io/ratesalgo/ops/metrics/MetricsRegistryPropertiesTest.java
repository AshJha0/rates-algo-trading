package io.ratesalgo.ops.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/**
 * The histogram rendered in Prometheus text format against a brute-force count: each cumulative
 * bucket {@code le="b"} counts the observations at or below b, {@code +Inf} and {@code _count}
 * count all of them and {@code _sum} adds them; plus the number and text escaping rules.
 */
class MetricsRegistryPropertiesTest {

  @Property(tries = 200, seed = "3")
  void cumulativeBucketsCountEveryObservationAtOrBelowTheirBound(
      @ForAll @Size(min = 1, max = 6) final List<@IntRange(min = 1, max = 40) Integer> steps,
      @ForAll @Size(max = 80) final List<@IntRange(min = -5, max = 250) Integer> values) {
    final double[] bounds = new double[steps.size()];
    double b = 0;
    for (int i = 0; i < bounds.length; i++) {
      b += steps.get(i);
      bounds[i] = b;
    }
    final MetricsRegistry r = new MetricsRegistry();
    final MetricsRegistry.Histogram h = r.histogram("lat_seconds", "Latency", bounds, "stage", "x");
    long sum = 0;
    for (final int v : values) {
      h.observe(v);
      sum += v;
    }
    final List<String> expected = new ArrayList<>();
    expected.add("# HELP lat_seconds Latency");
    expected.add("# TYPE lat_seconds histogram");
    for (final double bound : bounds) {
      long atOrBelow = 0;
      for (final int v : values) {
        if (v <= bound) {
          atOrBelow++;
        }
      }
      expected.add("lat_seconds_bucket{stage=\"x\",le=\"" + (long) bound + "\"} " + atOrBelow);
    }
    expected.add("lat_seconds_bucket{stage=\"x\",le=\"+Inf\"} " + values.size());
    expected.add("lat_seconds_sum{stage=\"x\"} " + sum);
    expected.add("lat_seconds_count{stage=\"x\"} " + values.size());
    assertEquals(expected, r.render().lines().toList());
    assertEquals(values.size(), h.count());
  }

  @Test
  void numbersPrintAsIntegersOnlyWhenWholeAndSmallerThanTenToTheFifteen() {
    final MetricsRegistry r = new MetricsRegistry();
    r.gauge("a", "a").set(42.0);
    r.gauge("b", "b").set(-3.0);
    r.gauge("c", "c").set(0.5);
    r.gauge("d", "d").set(1e15);
    r.gauge("e", "e").set(999_999_999_999_999.0);
    r.gauge("f", "f").set(Double.POSITIVE_INFINITY);
    final String text = r.render();
    assertTrue(text.contains("\na 42\n"), text);
    assertTrue(text.contains("\nb -3\n"), text);
    assertTrue(text.contains("\nc 0.5\n"), text);
    assertTrue(text.contains("\nd 1.0E15\n"), text);
    assertTrue(text.contains("\ne 999999999999999\n"), text);
    assertTrue(text.contains("\nf +Inf\n"), text);
  }

  @Test
  void helpAndLabelTextIsEscaped() {
    final MetricsRegistry r = new MetricsRegistry();
    r.counter("x_total", "line one\nback\\slash", "path", "a\\b\nc\"d").inc();
    r.counter("x_total", "ignored", "path", "plain").add(0);
    final List<String> lines = r.render().lines().toList();
    assertEquals("# HELP x_total line one\\nback\\\\slash", lines.get(0));
    assertEquals("x_total{path=\"a\\\\b\\nc\\\"d\"} 1", lines.get(2));
    assertEquals("x_total{path=\"plain\"} 0", lines.get(3));
  }

  @Test
  void twoLabelsAreJoinedByACommaAndBoundsMustStrictlyAscend() {
    final MetricsRegistry r = new MetricsRegistry();
    r.counter("y_total", "y", "a", "1", "b", "2").add(5);
    assertTrue(r.render().contains("y_total{a=\"1\",b=\"2\"} 5"), r.render());
    assertThrows(IllegalArgumentException.class, () -> r.histogram("h", "h", new double[] {1, 1}));
    assertThrows(IllegalArgumentException.class, () -> r.counter("z", "z", "9bad", "v"));
    final MetricsRegistry.Gauge g = r.gauge("g", "g");
    g.set(2.5);
    assertEquals(2.5, g.get(), 0.0);
    g.bind(() -> 7.0);
    assertEquals(7.0, g.get(), 0.0, "a bound supplier wins over the set value");
  }
}
