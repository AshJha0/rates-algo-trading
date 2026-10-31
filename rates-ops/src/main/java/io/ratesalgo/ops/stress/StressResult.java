package io.ratesalgo.ops.stress;

import java.util.Locale;

/**
 * Outcome of a stress run (API_SPEC.md Section 17.13). Latencies are nanoseconds from each
 * message's intended send time to the sink's return.
 *
 * @param frames messages offered
 * @param accepted messages the sink accepted
 * @param sourcePeakPerSecond the journal's busiest-window rate
 * @param targetPeakPerSecond the rate the harness drove that window at
 * @param multiplier the compression factor
 * @param p50Nanos median latency
 * @param p99Nanos 99th percentile
 * @param p999Nanos 99.9th percentile
 * @param maxNanos worst latency
 * @param durationNanos wall time of the run
 */
public record StressResult(long frames, long accepted, double sourcePeakPerSecond, double targetPeakPerSecond,
    double multiplier, long p50Nanos, long p99Nanos, long p999Nanos, long maxNanos, long durationNanos) {

  /** Accepted over offered; 1 when nothing was offered. */
  public double acceptanceRate() {
    return frames == 0 ? 1.0 : (double) accepted / frames;
  }

  /** One-line summary for reports. */
  public String summary() {
    return String.format(Locale.ROOT,
        "frames=%d accepted=%d acceptance=%.4f source-peak=%.1f/s target-peak=%.1f/s x%.1f "
            + "latency p50=%dns p99=%dns p99.9=%dns max=%dns",
        frames, accepted, acceptanceRate(), sourcePeakPerSecond, targetPeakPerSecond, multiplier, p50Nanos,
        p99Nanos, p999Nanos, maxNanos);
  }
}
