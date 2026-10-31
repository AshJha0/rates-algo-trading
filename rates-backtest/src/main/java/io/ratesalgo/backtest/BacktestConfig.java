package io.ratesalgo.backtest;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.ClobFillModel;
import io.ratesalgo.backtest.model.CompetitorModel;
import io.ratesalgo.backtest.model.HedgeCostModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.model.Preset;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.model.RfqOutcomeModel;
import io.ratesalgo.backtest.pnl.CarryModel;
import java.nio.file.Path;
import java.util.List;

/**
 * Everything a back-test run needs besides the strategy (API_SPEC.md Section 16.1).
 *
 * @param journals input journal files, replayed in order as one stream
 * @param instruments the instrument universe
 * @param seed seed of the outcome streams; with the journals it fixes every random draw
 * @param bandPoint the assumption band point the RFQ outcomes are settled at
 * @param outcomes RFQ outcome model (hit rate at {@code bandPoint}, competitors, seed)
 * @param strategyHitRate hit-rate model offered to strategies (always the central point)
 * @param hedgeCost market-order fill model
 * @param clob limit-order queue model
 * @param carry carry and roll hook
 * @param calendar converts a session date into scheduled events
 * @param clientsPerTier client ids 1..n per tier: tier = 1 + (clientId - 1) / clientsPerTier
 * @param quoteLatencyNanos time from RFQ receipt to our quote being sent
 * @param clientDecisionNanos time from our quote to the client's decision
 * @param orderLatencyNanos time from order to fill or acknowledgement
 * @param markoutHorizonsNanos markout horizons
 * @param outputDir where to write the results journal and tables; null for none
 */
public record BacktestConfig(
    List<Path> journals,
    InstrumentSet instruments,
    long seed,
    BandPoint bandPoint,
    RfqOutcomeModel outcomes,
    HitRateModel strategyHitRate,
    HedgeCostModel hedgeCost,
    ClobFillModel clob,
    CarryModel carry,
    CalendarSpec calendar,
    int clientsPerTier,
    long quoteLatencyNanos,
    long clientDecisionNanos,
    long orderLatencyNanos,
    long[] markoutHorizonsNanos,
    Path outputDir) {

  /** Markout horizons of the daily report: 1 s, 10 s, 1 min, 5 min. */
  public static final long[] DEFAULT_HORIZONS = {
    1_000_000_000L, 10_000_000_000L, 60_000_000_000L, 300_000_000_000L
  };

  /** Labels of {@link #DEFAULT_HORIZONS}. */
  public static final List<String> HORIZON_LABELS = List.of("1s", "10s", "1min", "5min");

  /** Scheduled-event times for a session, from preset {@code usd_rfq_flow}. */
  public record CalendarSpec(int utcOffsetHours, double[] minutesEt, double halfWidthMinutes) {
    public CalendarSpec {
      minutesEt = minutesEt.clone();
    }

    @Override
    public double[] minutesEt() {
      return minutesEt.clone();
    }

    @Override
    public boolean equals(final Object o) {
      return o instanceof CalendarSpec c
          && utcOffsetHours == c.utcOffsetHours
          && java.util.Arrays.equals(minutesEt, c.minutesEt)
          && Double.compare(halfWidthMinutes, c.halfWidthMinutes) == 0;
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.hashCode(minutesEt) * 31 + utcOffsetHours;
    }

    @Override
    public String toString() {
      return "CalendarSpec[" + utcOffsetHours + " " + java.util.Arrays.toString(minutesEt) + "]";
    }

    public static CalendarSpec fromPreset(final Preset flow) {
      final int n = (int) flow.value("event_count");
      final double[] minutes = new double[n];
      for (int i = 0; i < n; i++) {
        minutes[i] = flow.value("event_" + i + "_minute_et");
      }
      return new CalendarSpec(
          (int) flow.value("utc_offset_hours"), minutes, flow.value("event_half_width_minutes"));
    }
  }

  public BacktestConfig {
    journals = List.copyOf(journals);
    markoutHorizonsNanos = markoutHorizonsNanos.clone();
    if (journals.isEmpty()) {
      throw new IllegalArgumentException("at least one journal is required");
    }
    if (clientsPerTier <= 0) {
      throw new IllegalArgumentException("clientsPerTier must be positive");
    }
  }

  @Override
  public long[] markoutHorizonsNanos() {
    return markoutHorizonsNanos.clone();
  }

  @Override
  public boolean equals(final Object o) {
    return o == this;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public String toString() {
    return "BacktestConfig[" + journals + " seed=" + seed + " band=" + bandPoint + "]";
  }

  /** The standard configuration built from the presets file. */
  public static BacktestConfig fromPresets(
      final Presets presets,
      final List<Path> journals,
      final long seed,
      final BandPoint point,
      final Path outputDir) {
    final Preset hit = presets.get(HitRateModel.PRESET);
    final Preset market = presets.get("usd_synthetic_market");
    final Preset hedge = presets.get(HedgeCostModel.PRESET);
    final RfqOutcomeModel outcomes =
        new RfqOutcomeModel(
            HitRateModel.fromPreset(hit, point),
            CompetitorModel.fromPreset(presets.get(CompetitorModel.PRESET)),
            seed);
    return new BacktestConfig(
        journals,
        InstrumentSet.usdDefault(),
        seed,
        point,
        outcomes,
        HitRateModel.fromPreset(hit, BandPoint.CENTRAL),
        HedgeCostModel.fromPreset(hedge),
        new ClobFillModel(hedge.value("queue_trade_share")),
        CarryModel.NONE,
        CalendarSpec.fromPreset(presets.get("usd_rfq_flow")),
        (int) market.value("clients_per_tier"),
        Math.round(market.value("quote_latency_ms") * 1e6),
        Math.round(market.value("client_decision_ms") * 1e6),
        Math.round(hedge.value("order_latency_ms") * 1e6),
        DEFAULT_HORIZONS,
        outputDir);
  }

  /** The same configuration with another output directory. */
  public BacktestConfig withOutputDir(final Path dir) {
    return new BacktestConfig(
        journals, instruments, seed, bandPoint, outcomes, strategyHitRate, hedgeCost, clob, carry,
        calendar, clientsPerTier, quoteLatencyNanos, clientDecisionNanos, orderLatencyNanos,
        markoutHorizonsNanos, dir);
  }
}
