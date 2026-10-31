package io.ratesalgo.bench;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import org.HdrHistogram.Histogram;

/**
 * Writes a latency run as JSON (schema {@code rates-bench-latency/1}) and as rows for the results
 * table in docs/BENCHMARKS.md, and reads histograms back from the JSON.
 *
 * <p>Each interval section holds the sample count and p50, p99, p99.9, p99.99, max and mean in
 * microseconds, plus the full histogram as base64 of HdrHistogram's compressed encoding, so a
 * reader can compute any other percentile ({@link #readHistogram}).
 */
public final class LatencyReport {

  /** JSON schema name written into every report. */
  public static final String SCHEMA = "rates-bench-latency/1";

  private LatencyReport() {}

  /** Microseconds with three decimals from a nanosecond value. */
  static String micros(final long nanos) {
    return String.format(Locale.ROOT, "%.3f", nanos / 1_000.0);
  }

  /** The JSON object for one interval's histogram (nanosecond values), as the harness writes it. */
  public static String intervalJson(final Histogram h) {
    final StringBuilder b = new StringBuilder(256);
    b.append("{\"samples\": ").append(h.getTotalCount());
    if (h.getTotalCount() > 0) {
      b.append(", \"p50Us\": ").append(micros(h.getValueAtPercentile(50.0)));
      b.append(", \"p99Us\": ").append(micros(h.getValueAtPercentile(99.0)));
      b.append(", \"p999Us\": ").append(micros(h.getValueAtPercentile(99.9)));
      b.append(", \"p9999Us\": ").append(micros(h.getValueAtPercentile(99.99)));
      b.append(", \"maxUs\": ").append(micros(h.getMaxValue()));
      b.append(", \"meanUs\": ").append(String.format(Locale.ROOT, "%.3f", h.getMean() / 1_000.0));
      b.append(", \"p9999Supported\": ").append(h.getTotalCount() >= 100_000);
    }
    b.append(", \"histogram\": \"").append(encode(h)).append("\"}");
    return b.toString();
  }

  /** HdrHistogram compressed encoding, base64. */
  static String encode(final Histogram h) {
    final ByteBuffer buffer = ByteBuffer.allocate(h.getNeededByteBufferCapacity());
    final int length = h.encodeIntoCompressedByteBuffer(buffer);
    final byte[] bytes = new byte[length];
    buffer.flip();
    buffer.get(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  /**
   * Reads the histogram of section {@code name} ("decodeToEncode", "scheduledToEncode" or
   * "generatorLateness") from a report.
   *
   * @throws IllegalArgumentException if the section or its histogram is missing or unreadable
   */
  public static Histogram readHistogram(final String json, final String name) {
    final Matcher m =
        Pattern.compile("\"" + Pattern.quote(name) + "\":\\s*\\{[^}]*?\"histogram\":\\s*\"([A-Za-z0-9+/=]*)\"")
            .matcher(json);
    if (!m.find()) {
      throw new IllegalArgumentException("report has no histogram for " + name);
    }
    try {
      return Histogram.decodeFromCompressedByteBuffer(
          ByteBuffer.wrap(Base64.getDecoder().decode(m.group(1))), BenchQuoter.HIGHEST_NANOS);
    } catch (final DataFormatException | RuntimeException e) {
      throw new IllegalArgumentException("histogram for " + name + " is not readable", e);
    }
  }

  /** JSON string literal for {@code s}. */
  static String quote(final String s) {
    final StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }

  /** One row of the docs/BENCHMARKS.md results table. */
  static String markdownRow(
      final String date,
      final MachineInfo machine,
      final String interval,
      final int rate,
      final Histogram h,
      final long allocationDelta,
      final String reportFile) {
    final String machineText = (machine.model() + ", " + machine.cpu() + ", " + machine.os()).replace("|", "/");
    return "| "
        + date
        + " | "
        + machine.commit()
        + " | "
        + machineText
        + " | "
        + interval
        + " | "
        + rate
        + "/s | "
        + h.getTotalCount()
        + " | "
        + micros(h.getValueAtPercentile(50.0))
        + " | "
        + micros(h.getValueAtPercentile(99.0))
        + " | "
        + micros(h.getValueAtPercentile(99.9))
        + " | "
        + micros(h.getValueAtPercentile(99.99))
        + " | "
        + micros(h.getMaxValue())
        + " | "
        + allocationDelta
        + " | "
        + reportFile
        + " |";
  }
}
