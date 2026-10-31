package io.ratesalgo.strategy.report;

import io.ratesalgo.backtest.util.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The desk section of the daily report (feature 7; API_SPEC.md Section 19.10), in JSON and
 * Markdown: headline (P&amp;L per DV01 traded, hedge ratio, inventory half-life, limit
 * utilisation), the capture-versus-cost waterfall, and hit rate by tier, product and tenor.
 *
 * <p>The existing {@code DailyReport} has no extension point for an extra section, so this
 * section is written next to it as {@code desk_report.json} and {@code desk_report.md}
 * ({@link DeskReportCli}).
 */
public final class DeskReport {

  /** Waterfall lines in order; they sum to position P&amp;L minus the residual. */
  public static final List<String> WATERFALL =
      List.of("spread_capture", "adverse_selection", "other_inventory", "hedge_cost", "carry", "funding", "fees");

  private final String date;
  private final String strategy;
  private final DeskMetrics metrics;

  public DeskReport(final String date, final String strategy, final DeskMetrics metrics) {
    this.date = date;
    this.strategy = strategy;
    this.metrics = metrics;
  }

  /** The section as an ordered map. */
  public Map<String, Object> toMap() {
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("date", date);
    m.put("strategy", strategy);
    final Map<String, Object> head = new LinkedHashMap<>();
    for (final String k :
        List.of("pnl", "client_dv01_traded", "pnl_per_dv01", "capture_per_dv01", "hedge_ratio", "hedges",
            "inventory_half_life_s", "mean_abs_net_dv01", "limit_utilisation", "balance_sheet_charge")) {
      head.put(k, round(metrics.get(k)));
    }
    m.put("headline", head);
    final Map<String, Object> w = new LinkedHashMap<>();
    double sum = 0;
    for (final String k : WATERFALL) {
      w.put(k, round(metrics.get(k)));
      sum += metrics.get(k);
    }
    w.put("total", round(sum));
    w.put("residual", round(metrics.get("residual")));
    m.put("waterfall", w);
    final Map<String, Object> hit = new LinkedHashMap<>();
    for (final Map.Entry<String, Map<String, double[]>> t : metrics.hitRateCounts().entrySet()) {
      final Map<String, Object> row = new LinkedHashMap<>();
      for (final Map.Entry<String, double[]> c : t.getValue().entrySet()) {
        final double[] v = c.getValue();
        final Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("quoted", (long) v[0]);
        cell.put("won", (long) v[1]);
        cell.put("hit_rate", round(v[1] / v[0]));
        row.put(c.getKey(), cell);
      }
      hit.put(t.getKey(), row);
    }
    m.put("hit_rate", hit);
    return m;
  }

  public String toJson() {
    return Json.write(toMap());
  }

  /** The section as Markdown. */
  public String toMarkdown() {
    final StringBuilder sb = new StringBuilder();
    sb.append("## Desk view, ").append(date).append(", strategy ").append(strategy).append("\n\n");
    sb.append("Simulator consistency check on a synthetic session (DR-008), not evidence of edge.\n\n");
    sb.append("| Headline | Value |\n|---|---:|\n");
    row(sb, "P&L, USD", metrics.get("pnl"));
    row(sb, "Client DV01 traded, USD per bp", metrics.get("client_dv01_traded"));
    row(sb, "P&L per DV01 traded, bp", metrics.get("pnl_per_dv01"));
    row(sb, "Spread capture per DV01 traded, bp", metrics.get("capture_per_dv01"));
    row(sb, "Hedge ratio (hedged DV01 / client DV01)", metrics.get("hedge_ratio"));
    row(sb, "Hedge orders", metrics.get("hedges"));
    row(sb, "Inventory half-life, s (median)", metrics.get("inventory_half_life_s"));
    row(sb, "Mean absolute net DV01", metrics.get("mean_abs_net_dv01"));
    row(sb, "Largest net DV01 / limit", metrics.get("limit_utilisation"));
    sb.append("\n| Waterfall line | USD |\n|---|---:|\n");
    double sum = 0;
    for (final String k : WATERFALL) {
      row(sb, k.replace('_', ' '), metrics.get(k));
      sum += metrics.get(k);
    }
    row(sb, "total (position P&L less residual)", sum);
    sb.append("\n| Tier | Product and tenor | Quoted | Won | Hit rate |\n|---|---|---:|---:|---:|\n");
    for (final Map.Entry<String, Map<String, double[]>> t : metrics.hitRateCounts().entrySet()) {
      for (final Map.Entry<String, double[]> c : t.getValue().entrySet()) {
        final double[] v = c.getValue();
        sb.append(
            String.format(
                Locale.ROOT, "| %s | %s | %d | %d | %.3f |%n", t.getKey(), c.getKey(), (long) v[0], (long) v[1],
                v[1] / v[0]));
      }
    }
    return sb.toString();
  }

  private static void row(final StringBuilder sb, final String label, final double v) {
    sb.append(String.format(Locale.ROOT, "| %s | %,.4f |%n", label, v));
  }

  private static double round(final double v) {
    return Math.round(v * 1e4) / 1e4;
  }
}
