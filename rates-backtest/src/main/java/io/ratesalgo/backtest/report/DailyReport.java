package io.ratesalgo.backtest.report;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.FillRecord;
import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.backtest.util.Yaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The daily back-test report in JSON and Markdown (API_SPEC.md Section 16.9).
 *
 * <p>Sections, in order: run identity; P&amp;L by product and attribution line; hit rate and
 * decline rate by client tier; markouts at 1 s, 10 s, 1 min and 5 min by tier and by product, in
 * USD and in bp; hedge slippage; router fill quality (a hook until the P4 router records its
 * decisions); latency percentiles (from the latency harness JSON when the configured file exists);
 * limit utilisation; client analytics (hits and misses per client, money left on the table
 * against the cover, near misses that would have won at the cover, response time against win
 * rate, decline rates); daily-P&amp;L volatility, Sharpe ratio and maximum drawdown across the
 * assumption band ({@link BandRisk}, a simulator consistency check). Every number is computed from the run's records; nothing is typed in.
 */
public final class DailyReport {

  /** Limits the utilisation section is measured against, and optional inputs. */
  public record Settings(double netDv01Limit, double positionLimitMm, Path latencyJson) {}

  /** Router fill-quality rows; empty until the P4 router is wired. */
  @FunctionalInterface
  public interface RouterFillQuality {
    RouterFillQuality NONE = () -> List.of();

    List<Map<String, Object>> rows();
  }

  private final BacktestResult r;
  private final InstrumentSet instruments;
  private final Settings settings;
  private final RouterFillQuality router;
  private final List<Map<String, Object>> bandRisk;

  public DailyReport(
      final BacktestResult result,
      final InstrumentSet instruments,
      final Settings settings,
      final RouterFillQuality router) {
    this(result, instruments, settings, router, List.of());
  }

  private DailyReport(
      final BacktestResult result,
      final InstrumentSet instruments,
      final Settings settings,
      final RouterFillQuality router,
      final List<Map<String, Object>> bandRisk) {
    this.r = result;
    this.instruments = instruments;
    this.settings = settings;
    this.router = router;
    this.bandRisk = bandRisk;
  }

  /** The same report with the {@link BandRisk} rows of the run's band points. */
  public DailyReport withBandRisk(final List<Map<String, Object>> rows) {
    return new DailyReport(r, instruments, settings, router, List.copyOf(rows));
  }

  /** The report as a JSON-ready map. */
  public Map<String, Object> toMap() {
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("schema", "rates-backtest daily report v1");
    m.put("session_date", r.sessionDate().toString());
    m.put("strategy", r.strategy());
    m.put("band_point", r.bandPoint().label());
    m.put("seed", r.seed());
    m.put("input_chain_hash", Long.toHexString(r.inputChainHash()));
    m.put("output_chain_hash", Long.toHexString(r.outputChainHash()));
    m.put("pnl_by_product", pnlByProduct());
    m.put("hit_rate_by_tier", hitRateByTier());
    m.put("markouts_by_tier", markouts(true));
    m.put("markouts_by_product", markouts(false));
    m.put("hedge_slippage", hedgeSlippage());
    m.put("router_fill_quality", router.rows());
    m.put("latency", latency());
    m.put("limit_utilisation", limits());
    m.put("client_analytics", clients());
    m.put("response_time_vs_win_rate", responseBuckets());
    final Map<String, Object> risk = new LinkedHashMap<>();
    risk.put("label", BandRisk.LABEL);
    risk.put("rows", bandRisk);
    m.put("pnl_risk_across_band", risk);
    return m;
  }

  public String toJson() {
    return Json.write(toMap()) + "\n";
  }

  private List<Map<String, Object>> pnlByProduct() {
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Map.Entry<Integer, Attribution> e : new TreeMap<>(r.attributionByInstrument()).entrySet()) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("instrument", instruments.get(e.getKey()).name());
      putCents(row, e.getValue());
      rows.add(row);
    }
    final Map<String, Object> total = new LinkedHashMap<>();
    total.put("instrument", "TOTAL");
    putCents(total, r.total());
    rows.add(total);
    return rows;
  }

  private static void putCents(final Map<String, Object> row, final Attribution a) {
    for (final Map.Entry<String, Object> e : a.asMap().entrySet()) {
      row.put(e.getKey(), Prices.cents((Double) e.getValue()));
    }
  }

  private List<Map<String, Object>> hitRateByTier() {
    final TreeMap<Integer, long[]> byTier = new TreeMap<>();
    for (final RfqRecord q : r.rfqs()) {
      final long[] c = byTier.computeIfAbsent(q.tier(), k -> new long[3]);
      c[0]++;
      if (q.quoted()) {
        c[1]++;
      }
      if (q.won()) {
        c[2]++;
      }
    }
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Map.Entry<Integer, long[]> e : byTier.entrySet()) {
      final long[] c = e.getValue();
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("tier", (long) e.getKey());
      row.put("rfqs", c[0]);
      row.put("quoted", c[1]);
      row.put("won", c[2]);
      row.put("hit_rate", ratio(c[2], c[1]));
      row.put("decline_rate", ratio(c[0] - c[1], c[0]));
      rows.add(row);
    }
    return rows;
  }

  private List<Map<String, Object>> markouts(final boolean byTier) {
    final int h = BacktestConfig.HORIZON_LABELS.size();
    final TreeMap<String, double[]> sums = new TreeMap<>();
    for (final FillRecord f : r.fills()) {
      if (f.hedge()) {
        continue;
      }
      final Instrument i = instruments.get(f.instrumentId());
      final String key = byTier ? "tier " + f.tier() : i.name();
      final double[] s = sums.computeIfAbsent(key, k -> new double[2 * h + 1]);
      s[2 * h]++;
      final double dv01 = Math.abs(f.signedQuantity() * i.dv01PerUnit());
      for (int k = 0; k < h; k++) {
        s[k] += f.markout(k);
        s[h + k] += f.markout(k) / dv01;
      }
    }
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Map.Entry<String, double[]> e : sums.entrySet()) {
      final double[] s = e.getValue();
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put(byTier ? "tier" : "instrument", e.getKey());
      row.put("fills", (long) s[2 * h]);
      for (int k = 0; k < h; k++) {
        row.put("usd_" + BacktestConfig.HORIZON_LABELS.get(k), Prices.cents(s[k]));
        row.put("mean_bp_" + BacktestConfig.HORIZON_LABELS.get(k), round4(s[h + k] / s[2 * h]));
      }
      rows.add(row);
    }
    return rows;
  }

  private Map<String, Object> hedgeSlippage() {
    long n = 0;
    double cost = 0;
    double bp = 0;
    for (final FillRecord f : r.fills()) {
      if (f.hedge()) {
        final Instrument i = instruments.get(f.instrumentId());
        n++;
        cost += f.edge(i);
        bp += i.priceToBp(Math.signum((double) f.signedQuantity()) * (f.price() - f.mid()));
      }
    }
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("hedges", n);
    m.put("total_cost_usd", Prices.cents(cost));
    m.put("mean_slippage_bp", n == 0 ? 0.0 : round4(bp / n));
    return m;
  }

  private Map<String, Object> latency() {
    final Map<String, Object> m = new LinkedHashMap<>();
    final Path p = settings.latencyJson();
    if (p == null || !Files.isRegularFile(p)) {
      m.put("available", false);
      m.put("note", "no latency harness JSON at the configured path; the latency job writes it (P5 harness)");
      return m;
    }
    final Map<String, Object> raw = Yaml.loadMap(p);
    m.put("available", true);
    m.put("source", String.valueOf(p.getFileName()));
    m.put("machine", String.valueOf(raw.getOrDefault("machine", "unrecorded")));
    m.put("percentiles_ns", Yaml.map(raw, "percentiles_ns", p.toString()));
    return m;
  }

  private Map<String, Object> limits() {
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("net_dv01_limit_usd", settings.netDv01Limit());
    m.put("max_abs_net_dv01_usd", Prices.cents(r.maxAbsNetDv01()));
    m.put("net_dv01_utilisation", round4(r.maxAbsNetDv01() / settings.netDv01Limit()));
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Map.Entry<Integer, Long> e : new TreeMap<>(r.maxAbsPosition()).entrySet()) {
      final Map<String, Object> row = new LinkedHashMap<>();
      final double mm = (double) e.getValue() / InstrumentSet.MILLION;
      row.put("instrument", instruments.get(e.getKey()).name());
      row.put("max_abs_position_mm", round4(mm));
      row.put("utilisation", round4(mm / settings.positionLimitMm()));
      rows.add(row);
    }
    m.put("positions", rows);
    return m;
  }

  private List<Map<String, Object>> clients() {
    final TreeMap<Integer, double[]> by = new TreeMap<>();
    for (final RfqRecord q : r.rfqs()) {
      // rfqs, quoted, won, lost, left on table bp (won), near misses (lost within 0.05 bp)
      final double[] c = by.computeIfAbsent(q.clientId(), k -> new double[6]);
      c[0]++;
      if (q.quoted()) {
        c[1]++;
        if (q.won()) {
          c[2]++;
          c[4] += Math.max(0, -q.spreadToCoverBp());
        } else {
          c[3]++;
          if (q.spreadToCoverBp() <= 0.05) {
            c[5]++;
          }
        }
      }
    }
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Map.Entry<Integer, double[]> e : by.entrySet()) {
      final double[] c = e.getValue();
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("client_id", (long) e.getKey());
      row.put("rfqs", (long) c[0]);
      row.put("won", (long) c[2]);
      row.put("lost", (long) c[3]);
      row.put("hit_rate", ratio((long) c[2], (long) c[1]));
      row.put("decline_rate", ratio((long) (c[0] - c[1]), (long) c[0]));
      row.put("mean_left_on_table_bp", c[2] == 0 ? 0.0 : round4(c[4] / c[2]));
      row.put("near_misses_within_0_05bp", (long) c[5]);
      rows.add(row);
    }
    return rows;
  }

  private List<Map<String, Object>> responseBuckets() {
    final long[] edges = {0L, 10_000_000L, 100_000_000L, Long.MAX_VALUE};
    final String[] labels = {"<10ms", "10-100ms", ">=100ms"};
    final long[][] c = new long[labels.length][2];
    for (final RfqRecord q : r.rfqs()) {
      if (!q.quoted()) {
        continue;
      }
      for (int b = 0; b < labels.length; b++) {
        if (q.responseNanos() >= edges[b] && q.responseNanos() < edges[b + 1]) {
          c[b][0]++;
          if (q.won()) {
            c[b][1]++;
          }
        }
      }
    }
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (int b = 0; b < labels.length; b++) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("response_time", labels[b]);
      row.put("quoted", c[b][0]);
      row.put("win_rate", ratio(c[b][1], c[b][0]));
      rows.add(row);
    }
    return rows;
  }

  private static double ratio(final long a, final long b) {
    return b == 0 ? 0.0 : round4((double) a / b);
  }

  private static double round4(final double v) {
    return Math.round(v * 1e4) / 1e4;
  }

  /** The report as Markdown tables, one section per heading. */
  @SuppressWarnings("unchecked")
  public String toMarkdown() {
    final Map<String, Object> m = toMap();
    final StringBuilder sb = new StringBuilder(4096);
    sb.append("# Daily back-test report, ").append(m.get("session_date")).append("\n\n");
    sb.append("Strategy `").append(m.get("strategy")).append("`, band point `").append(m.get("band_point"))
        .append("`, seed ").append(m.get("seed")).append(". Input chain hash `").append(m.get("input_chain_hash"))
        .append("`, output chain hash `").append(m.get("output_chain_hash"))
        .append("`. Results are simulated; the band point names the declared win-probability assumption (DR-008).\n\n");
    section(sb, "P&L by product and attribution line (USD)", (List<Map<String, Object>>) m.get("pnl_by_product"));
    section(sb, "Hit rate by client tier", (List<Map<String, Object>>) m.get("hit_rate_by_tier"));
    section(sb, "Markouts by client tier", (List<Map<String, Object>>) m.get("markouts_by_tier"));
    section(sb, "Markouts by product", (List<Map<String, Object>>) m.get("markouts_by_product"));
    section(sb, "Hedge slippage", List.of((Map<String, Object>) m.get("hedge_slippage")));
    final List<Map<String, Object>> routerRows = (List<Map<String, Object>>) m.get("router_fill_quality");
    sb.append("## Router fill quality\n\n");
    if (routerRows.isEmpty()) {
      sb.append("Not available: the hook is wired when the P4 router records its decisions.\n\n");
    } else {
      table(sb, routerRows);
    }
    final Map<String, Object> lat = (Map<String, Object>) m.get("latency");
    sb.append("## Latency percentiles\n\n");
    if (Boolean.TRUE.equals(lat.get("available"))) {
      sb.append("From `").append(lat.get("source")).append("` on ").append(lat.get("machine")).append(".\n\n");
      final Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) lat.get("percentiles_ns"));
      table(sb, List.of(row));
    } else {
      sb.append(lat.get("note")).append(".\n\n");
    }
    final Map<String, Object> lim = new LinkedHashMap<>((Map<String, Object>) m.get("limit_utilisation"));
    final List<Map<String, Object>> positions = (List<Map<String, Object>>) lim.remove("positions");
    section(sb, "Limit utilisation", List.of(lim));
    table(sb, positions);
    section(sb, "Client analytics", (List<Map<String, Object>>) m.get("client_analytics"));
    section(sb, "Response time against win rate", (List<Map<String, Object>>) m.get("response_time_vs_win_rate"));
    sb.append(BandRisk.toMarkdown(bandRisk));
    return sb.toString();
  }

  private static void section(final StringBuilder sb, final String title, final List<Map<String, Object>> rows) {
    sb.append("## ").append(title).append("\n\n");
    table(sb, rows);
  }

  private static void table(final StringBuilder sb, final List<Map<String, Object>> rows) {
    if (rows.isEmpty()) {
      sb.append("No rows.\n\n");
      return;
    }
    final List<String> cols = new ArrayList<>(rows.get(0).keySet());
    sb.append("| ").append(String.join(" | ", cols)).append(" |\n|");
    sb.append(String.join("|", java.util.Collections.nCopies(cols.size(), "---"))).append("|\n");
    for (final Map<String, Object> row : rows) {
      final String[] cells = new String[cols.size()];
      for (int i = 0; i < cells.length; i++) {
        final Object v = row.get(cols.get(i));
        cells[i] = v instanceof Double d ? String.format(Locale.ROOT, "%s", trim(d)) : String.valueOf(v);
      }
      sb.append("| ").append(String.join(" | ", Arrays.asList(cells))).append(" |\n");
    }
    sb.append('\n');
  }

  private static String trim(final double d) {
    if (Double.compare(d, Math.rint(d)) == 0 && Math.abs(d) < 1e15) {
      return String.format(Locale.ROOT, "%.0f", d);
    }
    return String.format(Locale.ROOT, "%.4f", d).replaceAll("0+$", "");
  }
}
