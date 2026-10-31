package io.ratesalgo.ops.metrics;

import java.util.List;

/**
 * The metric names the platform exposes (API_SPEC.md Section 17.12). The list is the contract
 * with the dashboards and alert rules: tools/lint_dashboards.py refuses a dashboard or rule that
 * names anything else, and {@code MetricNamesTest} checks this list against
 * deploy/prometheus/metrics.txt.
 */
public final class MetricNames {

  /** Gauge, label {@code queue}: messages waiting in a ring buffer. */
  public static final String QUEUE_DEPTH = "rates_queue_depth";

  /** Histogram, label {@code stage}: seconds spent in one stage of the loop. */
  public static final String STAGE_LATENCY = "rates_stage_latency_seconds";

  /** Gauge, label {@code limit}: observed value over limit, 0 to 1 and above on breach. */
  public static final String LIMIT_UTILISATION = "rates_limit_utilisation_ratio";

  /** Gauge, labels {@code class} and {@code explained}: breaks in the latest reconciliation. */
  public static final String RECON_BREAKS = "rates_recon_breaks";

  /** Gauge: quotes live at venues. */
  public static final String QUOTES_LIVE = "rates_quotes_live";

  /** Gauge: 1 when the kill switch is engaged. */
  public static final String KILL_SWITCH = "rates_kill_switch_engaged";

  /** Gauge: 1 when quoting is enabled. */
  public static final String QUOTING_ENABLED = "rates_quoting_enabled";

  /** Counter: journal frames appended. */
  public static final String JOURNAL_FRAMES = "rates_journal_frames_total";

  /** Gauge, labels {@code name}, {@code role}, {@code colour}: always 1. */
  public static final String PROCESS_INFO = "rates_process_info";

  /** Every name above, in this order. */
  public static final List<String> ALL = List.of(QUEUE_DEPTH, STAGE_LATENCY, LIMIT_UTILISATION,
      RECON_BREAKS, QUOTES_LIVE, KILL_SWITCH, QUOTING_ENABLED, JOURNAL_FRAMES, PROCESS_INFO);

  private static final double[] LATENCY_BUCKETS = {
    0.000001, 0.000002, 0.000005, 0.00001, 0.00002, 0.00005, 0.0001, 0.0002, 0.0005, 0.001, 0.002,
    0.005, 0.01, 0.02, 0.05, 0.1
  };

  private MetricNames() {}

  /** Latency buckets in seconds: 1 microsecond to 100 milliseconds (a fresh copy). */
  public static double[] latencyBuckets() {
    return LATENCY_BUCKETS.clone();
  }
}
