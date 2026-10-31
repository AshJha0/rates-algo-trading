package io.ratesalgo.bench;

import java.nio.file.Path;

/**
 * Settings of one latency run.
 *
 * @param rate QuoteRequests per second the generator schedules
 * @param warmupSeconds seconds of load before measurement; samples are discarded
 * @param measureSeconds seconds of measured load
 * @param idle loop idle strategy: busy-spin, yield or backoff
 * @param journal whether the loop journals inbound and outbound messages to segmented journals
 * @param journalMode how the journals are written when {@code journal} is true: direct or ring
 * @param ringBytes size of each journal ring in ring mode, a power of two
 * @param segmentBytes journal segment size
 * @param cpu CPU to pin the loop thread to on Linux, or -1
 * @param label free text naming the run (for example the machine), copied into the report
 * @param outDir where the JSON and Markdown reports are written
 */
public record HarnessConfig(
    int rate,
    int warmupSeconds,
    int measureSeconds,
    String idle,
    boolean journal,
    String journalMode,
    int ringBytes,
    int segmentBytes,
    int cpu,
    String label,
    Path outDir) {

  public HarnessConfig {
    if (rate < 1 || rate > 10_000_000) {
      throw new IllegalArgumentException("rate must be 1 to 10,000,000 per second: " + rate);
    }
    if (warmupSeconds < 0 || measureSeconds < 1) {
      throw new IllegalArgumentException("warm-up must be >= 0 and measurement >= 1 second");
    }
    if (!"direct".equals(journalMode) && !"ring".equals(journalMode)) {
      throw new IllegalArgumentException("journal mode must be direct or ring: " + journalMode);
    }
  }

  /** Defaults from docs/BENCHMARKS.md: 50,000 per second, 60 s warm-up, 300 s measured. */
  public static HarnessConfig defaults() {
    return new HarnessConfig(
        50_000, 60, 300, "busy-spin", true, "direct", 64 << 20, 512 << 20, -1, "local", Path.of("target", "latency"));
  }

  /** Nanoseconds between scheduled sends. */
  public long intervalNanos() {
    return 1_000_000_000L / rate;
  }

  /** Messages sent during warm-up. */
  public long warmupMessages() {
    return (long) rate * warmupSeconds;
  }

  /** Messages sent during measurement. */
  public long measuredMessages() {
    return (long) rate * measureSeconds;
  }

  /**
   * Parses {@code --name=value} arguments over the defaults.
   *
   * @throws IllegalArgumentException for an unknown name or a malformed value
   */
  public static HarnessConfig parse(final String[] args) {
    HarnessConfig c = defaults();
    for (final String arg : args) {
      if (!arg.startsWith("--") || arg.indexOf('=') < 0) {
        throw new IllegalArgumentException("expected --name=value, got " + arg);
      }
      final String name = arg.substring(2, arg.indexOf('='));
      final String value = arg.substring(arg.indexOf('=') + 1);
      c =
          switch (name) {
            case "rate" ->
                new HarnessConfig(Integer.parseInt(value), c.warmupSeconds, c.measureSeconds, c.idle, c.journal, c.journalMode, c.ringBytes, c.segmentBytes, c.cpu, c.label, c.outDir);
            case "warmup-seconds" ->
                new HarnessConfig(c.rate, Integer.parseInt(value), c.measureSeconds, c.idle, c.journal, c.journalMode, c.ringBytes, c.segmentBytes, c.cpu, c.label, c.outDir);
            case "measure-seconds" ->
                new HarnessConfig(c.rate, c.warmupSeconds, Integer.parseInt(value), c.idle, c.journal, c.journalMode, c.ringBytes, c.segmentBytes, c.cpu, c.label, c.outDir);
            case "idle" ->
                new HarnessConfig(c.rate, c.warmupSeconds, c.measureSeconds, value, c.journal, c.journalMode, c.ringBytes, c.segmentBytes, c.cpu, c.label, c.outDir);
            case "journal" ->
                new HarnessConfig(c.rate, c.warmupSeconds, c.measureSeconds, c.idle, parseBoolean(value), c.journalMode, c.ringBytes, c.segmentBytes, c.cpu, c.label, c.outDir);
            case "journal-mode" ->
                new HarnessConfig(c.rate, c.warmupSeconds, c.measureSeconds, c.idle, c.journal, value, c.ringBytes, c.segmentBytes, c.cpu, c.label, c.outDir);
            case "ring-bytes" ->
                new HarnessConfig(c.rate, c.warmupSeconds, c.measureSeconds, c.idle, c.journal, c.journalMode, Integer.parseInt(value), c.segmentBytes, c.cpu, c.label, c.outDir);
            case "segment-bytes" ->
                new HarnessConfig(c.rate, c.warmupSeconds, c.measureSeconds, c.idle, c.journal, c.journalMode, c.ringBytes, Integer.parseInt(value), c.cpu, c.label, c.outDir);
            case "cpu" ->
                new HarnessConfig(c.rate, c.warmupSeconds, c.measureSeconds, c.idle, c.journal, c.journalMode, c.ringBytes, c.segmentBytes, Integer.parseInt(value), c.label, c.outDir);
            case "label" ->
                new HarnessConfig(c.rate, c.warmupSeconds, c.measureSeconds, c.idle, c.journal, c.journalMode, c.ringBytes, c.segmentBytes, c.cpu, value, c.outDir);
            case "out" ->
                new HarnessConfig(c.rate, c.warmupSeconds, c.measureSeconds, c.idle, c.journal, c.journalMode, c.ringBytes, c.segmentBytes, c.cpu, c.label, Path.of(value));
            default -> throw new IllegalArgumentException("unknown option --" + name);
          };
    }
    return c;
  }

  private static boolean parseBoolean(final String value) {
    return switch (value) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException("expected true or false: " + value);
    };
  }
}
