package io.ratesalgo.backtest;

import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.Prices;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a run's tables (API_SPEC.md Section 16.9): {@code fills.csv}, {@code rfqs.csv}, {@code
 * pnl.csv} and {@code summary.json}. Currency amounts carry six decimals in the CSV files so that
 * {@code ratesresearch.backtest_analysis} can rebuild the attribution from the fills and reconcile
 * it to the cent; prices carry nine (the mantissa exponent).
 */
public final class ResultsWriter {

  private ResultsWriter() {}

  public static void write(final BacktestResult r, final InstrumentSet instruments, final Path dir) {
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("fills.csv"), fills(r, instruments), StandardCharsets.UTF_8);
      Files.writeString(dir.resolve("rfqs.csv"), rfqs(r, instruments), StandardCharsets.UTF_8);
      Files.writeString(dir.resolve("pnl.csv"), pnl(r, instruments), StandardCharsets.UTF_8);
      Files.writeString(dir.resolve("summary.json"), Json.write(summary(r)) + "\n", StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot write results to " + dir, e);
    }
  }

  static String fills(final BacktestResult r, final InstrumentSet instruments) {
    final StringBuilder sb =
        new StringBuilder(
            "fill_id,time_nanos,instrument_id,instrument,kind,signed_quantity,price,mid,fee,ref_id,client_id,tier,edge");
    for (final String h : BacktestConfig.HORIZON_LABELS) {
      sb.append(",markout_").append(h);
    }
    sb.append('\n');
    for (final FillRecord f : r.fills()) {
      final Instrument i = instruments.get(f.instrumentId());
      sb.append(f.fillId()).append(',').append(f.timeNanos()).append(',').append(f.instrumentId()).append(',')
          .append(i.name()).append(',').append(f.hedge() ? "hedge" : "client").append(',')
          .append(f.signedQuantity()).append(',').append(px(f.price())).append(',').append(px(f.mid()))
          .append(',').append(money(f.fee())).append(',').append(f.refId()).append(',').append(f.clientId())
          .append(',').append(f.tier()).append(',').append(money(f.edge(i)));
      for (final double m : f.markouts()) {
        sb.append(',').append(money(m));
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  static String rfqs(final BacktestResult r, final InstrumentSet instruments) {
    final StringBuilder sb =
        new StringBuilder(
            "rfq_id,received_nanos,client_id,tier,instrument_id,instrument,client_side,size_mm,dealers,"
                + "in_fixing_window,quoted,response_nanos,our_price,mid_at_quote,our_spread_bp,cover_bp,"
                + "spread_to_cover_bp,win_probability,won,traded_price\n");
    for (final RfqRecord q : r.rfqs()) {
      sb.append(q.rfqId()).append(',').append(q.receivedNanos()).append(',').append(q.clientId()).append(',')
          .append(q.tier()).append(',').append(q.instrumentId()).append(',')
          .append(instruments.get(q.instrumentId()).name()).append(',').append(q.clientBuys() ? "buy" : "sell")
          .append(',').append(fmt(q.sizeMillions(), 0)).append(',').append(q.dealers()).append(',')
          .append(q.inFixingWindow() ? 1 : 0).append(',').append(q.quoted() ? 1 : 0).append(',')
          .append(q.responseNanos()).append(',');
      if (q.quoted()) {
        sb.append(px(Prices.toDouble(q.ourPriceMantissa()))).append(',').append(px(q.midAtQuote())).append(',')
            .append(fmt(q.ourSpreadBp(), 6)).append(',').append(fmt(q.coverBp(), 6)).append(',')
            .append(fmt(q.spreadToCoverBp(), 6)).append(',').append(fmt(q.winProbability(), 6)).append(',')
            .append(q.won() ? 1 : 0).append(',').append(px(Prices.toDouble(q.tradedPriceMantissa())));
      } else {
        sb.append(",,,,,,0,");
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  static String pnl(final BacktestResult r, final InstrumentSet instruments) {
    final StringBuilder sb =
        new StringBuilder(
            "instrument_id,instrument,spread_capture,inventory_mtm,hedge_cost,carry_roll,funding,fees,residual,position_pnl\n");
    for (final Map.Entry<Integer, Attribution> e : new java.util.TreeMap<>(r.attributionByInstrument()).entrySet()) {
      row(sb, e.getKey(), instruments.get(e.getKey()).name(), e.getValue());
    }
    row(sb, 0, "TOTAL", r.total());
    return sb.toString();
  }

  private static void row(final StringBuilder sb, final int id, final String name, final Attribution a) {
    sb.append(id).append(',').append(name).append(',').append(money(a.spreadCapture())).append(',')
        .append(money(a.inventoryMtm())).append(',').append(money(a.hedgeCost())).append(',')
        .append(money(a.carryRoll())).append(',').append(money(a.funding())).append(',').append(money(a.fees()))
        .append(',').append(money(a.residual())).append(',').append(money(a.positionPnl())).append('\n');
  }

  public static Map<String, Object> summary(final BacktestResult r) {
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("schema", "rates-backtest summary v1");
    m.put("strategy", r.strategy());
    m.put("band_point", r.bandPoint().label());
    m.put("seed", r.seed());
    m.put("session_date", r.sessionDate().toString());
    m.put("start_nanos", r.startNanos());
    m.put("end_nanos", r.endNanos());
    m.put("input_frames", r.inputFrames());
    m.put("input_chain_hash", Long.toHexString(r.inputChainHash()));
    m.put("output_chain_hash", Long.toHexString(r.outputChainHash()));
    m.put("rfqs", (long) r.rfqs().size());
    m.put("declines", r.declines());
    m.put("client_fills", r.clientFills());
    m.put("hedge_fills", r.hedgeFills());
    m.put("max_abs_net_dv01", r.maxAbsNetDv01());
    final Map<String, Object> attribution = new LinkedHashMap<>();
    for (final Map.Entry<String, Object> e : r.total().asMap().entrySet()) {
      attribution.put(e.getKey(), e.getValue());
    }
    m.put("attribution", attribution);
    m.put("position_pnl_cents", Prices.cents(r.total().positionPnl()));
    return m;
  }

  static String money(final double v) {
    return fmt(v, 6);
  }

  static String px(final double v) {
    return fmt(v, 9);
  }

  static String fmt(final double v, final int decimals) {
    final String s = String.format(Locale.ROOT, "%." + decimals + "f", v);
    return s.startsWith("-") && s.matches("-0\\.0*") ? s.substring(1) : s;
  }
}
