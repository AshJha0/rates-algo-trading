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
import io.ratesalgo.backtest.sim.SimDay;
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
    List<Long> markoutHorizonsNanos,
    Path outputDir) {

  /** Markout horizons of the daily report: 1 s, 10 s, 1 min, 5 min. */
  public static final List<Long> DEFAULT_HORIZONS =
      List.of(1_000_000_000L, 10_000_000_000L, 60_000_000_000L, 300_000_000_000L);

  /** Labels of {@link #DEFAULT_HORIZONS}. */
  public static final List<String> HORIZON_LABELS = List.of("1s", "10s", "1min", "5min");

  /** Scheduled-event times for a session, from preset {@code usd_rfq_flow}. */
  public record CalendarSpec(int utcOffsetHours, List<Double> minutesEt, double halfWidthMinutes) {
    public CalendarSpec {
      minutesEt = List.copyOf(minutesEt);
    }

    public static CalendarSpec fromPreset(final Preset flow) {
      final int n = (int) flow.value("event_count");
      final List<Double> minutes = new java.util.ArrayList<>();
      for (int i = 0; i < n; i++) {
        minutes.add(flow.value("event_" + i + "_minute_et"));
      }
      return new CalendarSpec(
          (int) flow.value("utc_offset_hours"), minutes, flow.value("event_half_width_minutes"));
    }
  }

  public BacktestConfig {
    journals = List.copyOf(journals);
    markoutHorizonsNanos = List.copyOf(markoutHorizonsNanos);
    if (journals.isEmpty()) {
      throw new IllegalArgumentException("at least one journal is required");
    }
    if (clientsPerTier <= 0) {
      throw new IllegalArgumentException("clientsPerTier must be positive");
    }
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

  /**
   * The configuration of one simulator v2 session (docs/research/SIM_V2_DESIGN.md section 10).
   * The market comes from the shared model behind {@code day}: instruments, RFQ outcomes by best
   * price, hedge fills and resting orders on the model's book. What stays in the presets file is
   * desk side or transport: the desk's latencies, the venue fee, the fixing calendar strategies
   * read, and the hit-rate model offered to strategies (a sensitivity, never the settlement).
   */
  public static BacktestConfig forSimV2(
      final Presets presets, final SimDay day, final List<Path> journals, final Path outputDir) {
    final Preset market = presets.get("usd_synthetic_market");
    final Preset hedge = presets.get(HedgeCostModel.PRESET);
    return new BacktestConfig(
        journals,
        InstrumentSet.simV2(day.model().spec().params()),
        day.model().seed(),
        BandPoint.CENTRAL,
        RfqOutcomeModel.simV2(day),
        HitRateModel.fromPreset(presets.get(HitRateModel.PRESET), BandPoint.CENTRAL),
        HedgeCostModel.simV2(day, hedge.value("fee_per_lot")),
        ClobFillModel.simV2(day),
        CarryModel.NONE,
        CalendarSpec.fromPreset(presets.get("usd_rfq_flow")),
        1,
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
