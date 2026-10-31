package io.ratesalgo.backtest;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.pnl.Attribution;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * The outcome of one back-test run.
 *
 * @param strategy strategy name
 * @param bandPoint band point the RFQs were settled at
 * @param seed outcome seed
 * @param startNanos time of the first input frame
 * @param endNanos time of the last event processed
 * @param inputFrames frames replayed
 * @param inputChainHash final chain hash of the input journals (the last journal's)
 * @param outputChainHash FNV-1a chain hash over every output message, the determinism fingerprint
 * @param rfqs every RFQ seen, in arrival order
 * @param fills every fill, in booking order, with markouts
 * @param attributionByInstrument attribution per instrument id, ascending
 * @param total attribution summed over instruments
 * @param maxAbsNetDv01 largest absolute net DV01 after any trade, major currency per bp
 * @param maxAbsPosition largest absolute position per instrument, minor units
 * @param declines RFQs not quoted
 */
public record BacktestResult(
    String strategy,
    BandPoint bandPoint,
    long seed,
    long startNanos,
    long endNanos,
    long inputFrames,
    long inputChainHash,
    long outputChainHash,
    List<RfqRecord> rfqs,
    List<FillRecord> fills,
    Map<Integer, Attribution> attributionByInstrument,
    Attribution total,
    double maxAbsNetDv01,
    Map<Integer, Long> maxAbsPosition,
    long declines) {

  public BacktestResult {
    rfqs = List.copyOf(rfqs);
    fills = List.copyOf(fills);
    attributionByInstrument = Map.copyOf(attributionByInstrument);
    maxAbsPosition = Map.copyOf(maxAbsPosition);
  }

  /** The UTC date of the first frame. */
  public LocalDate sessionDate() {
    return LocalDate.ofInstant(
        Instant.ofEpochSecond(startNanos / 1_000_000_000L), ZoneOffset.UTC);
  }

  public long clientFills() {
    return fills.stream().filter(f -> !f.hedge()).count();
  }

  public long hedgeFills() {
    return fills.stream().filter(FillRecord::hedge).count();
  }
}
