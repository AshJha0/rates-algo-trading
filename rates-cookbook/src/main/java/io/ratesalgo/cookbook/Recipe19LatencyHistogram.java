package io.ratesalgo.cookbook;

import io.ratesalgo.bench.LatencyReport;
import java.util.Locale;
import org.HdrHistogram.Histogram;

/** COOKBOOK.md recipe 19: read a latency histogram the way the harness writes it. */
public final class Recipe19LatencyHistogram {

  private Recipe19LatencyHistogram() {}

  private static String row(final String name, final Histogram h) {
    return String.format(
        Locale.ROOT,
        "%-10s samples=%d p50=%.1fus p99=%.1fus p99.9=%.1fus max=%.1fus",
        name,
        h.getTotalCount(),
        h.getValueAtPercentile(50.0) / 1_000.0,
        h.getValueAtPercentile(99.0) / 1_000.0,
        h.getValueAtPercentile(99.9) / 1_000.0,
        h.getMaxValue() / 1_000.0);
  }

  public static void main(final String[] args) {
    // 50,000 messages a second: one every 20 us. 9,999 take 10 us; one stalls for 10 ms.
    final long interval = 20_000L;
    final Histogram raw = new Histogram(10_000_000_000L, 3);
    final Histogram corrected = new Histogram(10_000_000_000L, 3);
    for (int i = 0; i < 9_999; i++) {
      raw.recordValue(10_000L);
      corrected.recordValueWithExpectedInterval(10_000L, interval);
    }
    raw.recordValue(10_000_000L);
    corrected.recordValueWithExpectedInterval(10_000_000L, interval);
    System.out.println(row("raw", raw));
    System.out.println(row("corrected", corrected));

    // a harness report holds each interval as a JSON object with a base64 histogram
    final String report = "{\"decodeToEncode\": " + LatencyReport.intervalJson(corrected) + "}";
    final Histogram back = LatencyReport.readHistogram(report, "decodeToEncode");
    System.out.println(row("from JSON", back));
    System.out.println("p99.99 supported: " + report.contains("\"p9999Supported\": true"));
  }
}
