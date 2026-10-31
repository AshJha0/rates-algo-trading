package io.ratesalgo.ops.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsRegistryTest {

  @Test
  void rendersPrometheusTextFormat() {
    final MetricsRegistry r = new MetricsRegistry();
    r.counter("rates_journal_frames_total", "Frames").add(3);
    r.gauge("rates_queue_depth", "Depth", "queue", "inbound").set(7);
    r.gauge("rates_limit_utilisation_ratio", "Use", "limit", "a\"b").set(0.25);
    final MetricsRegistry.Histogram h =
        r.histogram("rates_stage_latency_seconds", "Lat", new double[] {1, 2}, "stage", "price");
    h.observe(0.5);
    h.observe(1.5);
    h.observe(4);
    final String text = r.render();
    final String expected =
        """
        # HELP rates_journal_frames_total Frames
        # TYPE rates_journal_frames_total counter
        rates_journal_frames_total 3
        # HELP rates_queue_depth Depth
        # TYPE rates_queue_depth gauge
        rates_queue_depth{queue="inbound"} 7
        # HELP rates_limit_utilisation_ratio Use
        # TYPE rates_limit_utilisation_ratio gauge
        rates_limit_utilisation_ratio{limit="a\\\"b"} 0.25
        # HELP rates_stage_latency_seconds Lat
        # TYPE rates_stage_latency_seconds histogram
        rates_stage_latency_seconds_bucket{stage="price",le="1"} 1
        rates_stage_latency_seconds_bucket{stage="price",le="2"} 2
        rates_stage_latency_seconds_bucket{stage="price",le="+Inf"} 3
        rates_stage_latency_seconds_sum{stage="price"} 6
        rates_stage_latency_seconds_count{stage="price"} 3
        """;
    assertEquals(expected, text);
    assertEquals(3, h.count());
  }

  @Test
  void seriesAreReusedAndMisuseIsRefused() {
    final MetricsRegistry r = new MetricsRegistry();
    final MetricsRegistry.Counter c = r.counter("x_total", "x", "a", "1");
    assertSame(c, r.counter("x_total", "x", "a", "1"));
    assertThrows(IllegalArgumentException.class, () -> r.gauge("x_total", "x"));
    assertThrows(IllegalArgumentException.class, () -> r.counter("bad name", "x"));
    assertThrows(IllegalArgumentException.class, () -> r.counter("y", "x", "odd"));
    assertThrows(IllegalArgumentException.class, () -> r.counter("y", "x", "le", "1"));
    assertThrows(IllegalArgumentException.class, () -> r.histogram("h", "x", new double[] {2, 1}));
    assertThrows(IllegalArgumentException.class, () -> c.add(-1));
    final MetricsRegistry.Gauge g = r.gauge("g", "g");
    g.bind(() -> Double.NaN);
    final MetricsRegistry.Gauge inf = r.gauge("g_inf", "g");
    inf.set(Double.NEGATIVE_INFINITY);
    c.inc();
    final String text = r.render();
    assertTrue(text.contains("g NaN"), text);
    assertTrue(text.contains("g_inf -Inf"), text);
    assertTrue(text.contains("x_total{a=\"1\"} 1"), text);
  }

  @Test
  void namesMatchTheCatalogueFile() throws Exception {
    final List<String> file = Files.readAllLines(Path.of("../deploy/prometheus/metrics.txt")).stream()
        .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    assertEquals(MetricNames.ALL, file);
    assertEquals(16, MetricNames.latencyBuckets().length);
  }
}
