package io.ratesalgo.backtest.report;

import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.backtest.util.Yaml;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Daily-P&amp;L volatility, Sharpe ratio and maximum drawdown for each strategy at each point of
 * the declared assumption band (API_SPEC.md Section 16.9).
 *
 * <p>These are simulator consistency checks, not evidence of edge (DR-008): the outcome models
 * are declared assumptions, so the figures say how the strategy behaves if the assumptions hold
 * and how much that changes from the low to the high end of the band. The table is derived from
 * the daily P&amp;L series only; it never changes a ledger record.
 */
public final class BandRisk {

  /** The label every rendering of the table carries. */
  public static final String LABEL =
      "Simulator consistency check, not evidence of edge (DR-008): figures come from simulated outcomes"
          + " under the declared win-probability band. Sharpe uses a zero risk-free rate; the annualised"
          + " figure multiplies the daily ratio by the square root of 252.";

  private BandRisk() {}

  /**
   * One row per (band point, strategy) from daily P&amp;L series, in the iteration order of the
   * maps. A series shorter than two days gives a row with {@code days} and {@code mean} only.
   */
  public static List<Map<String, Object>> rows(final Map<String, Map<String, double[]>> dailyByBand) {
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Map.Entry<String, Map<String, double[]>> band : dailyByBand.entrySet()) {
      for (final Map.Entry<String, double[]> s : band.getValue().entrySet()) {
        final double[] x = s.getValue();
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("band_point", band.getKey());
        row.put("strategy", s.getKey());
        row.put("days", (long) x.length);
        if (x.length < 2) {
          row.put("mean_daily_pnl_usd", x.length == 0 ? 0.0 : Prices.cents(x[0]));
          rows.add(row);
          continue;
        }
        final Stats.PnlRisk r = Stats.pnlRisk(x);
        row.put("mean_daily_pnl_usd", Prices.cents(r.mean()));
        row.put("pnl_volatility_usd", Prices.cents(r.volatility()));
        row.put("sharpe_daily", ratio(r.sharpe()));
        row.put("sharpe_annualised", ratio(r.annualisedSharpe()));
        row.put("max_drawdown_usd", Prices.cents(r.maxDrawdown()));
        rows.add(row);
      }
    }
    return rows;
  }

  /** Rows from an experiment's results map ({@code band -> point -> strategies -> name -> daily_pnl}). */
  public static List<Map<String, Object>> fromExperimentResults(final Map<String, Object> results) {
    final Map<String, Map<String, double[]>> daily = new LinkedHashMap<>();
    for (final Map.Entry<String, Object> band : Yaml.asMap(results.get("band"), "band").entrySet()) {
      final Map<String, Object> strategies =
          Yaml.asMap(Yaml.asMap(band.getValue(), band.getKey()).get("strategies"), "strategies");
      final Map<String, double[]> byStrategy = new LinkedHashMap<>();
      for (final Map.Entry<String, Object> s : strategies.entrySet()) {
        final List<Object> values = Yaml.asList(Yaml.asMap(s.getValue(), s.getKey()).get("daily_pnl"), "daily_pnl");
        final double[] x = new double[values.size()];
        for (int i = 0; i < x.length; i++) {
          x[i] = ((Number) values.get(i)).doubleValue();
        }
        byStrategy.put(s.getKey(), x);
      }
      daily.put(band.getKey(), byStrategy);
    }
    return rows(daily);
  }

  /** The table as JSON with its label. */
  public static String toJson(final List<Map<String, Object>> rows) {
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("label", LABEL);
    m.put("rows", rows);
    return Json.write(m) + "\n";
  }

  /** The table as a Markdown section with its label. */
  public static String toMarkdown(final List<Map<String, Object>> rows) {
    final StringBuilder sb = new StringBuilder(1024);
    sb.append("## P&L risk across the assumption band\n\n").append(LABEL).append("\n\n");
    if (rows.isEmpty() || !rows.get(0).containsKey("sharpe_daily")) {
      sb.append("Needs at least two session days per band point; run with two or more journals.\n\n");
      return sb.toString();
    }
    final String[] cols = {
      "band_point", "strategy", "days", "mean_daily_pnl_usd", "pnl_volatility_usd", "sharpe_daily",
      "sharpe_annualised", "max_drawdown_usd"
    };
    sb.append("| ").append(String.join(" | ", cols)).append(" |\n|");
    sb.append("---|".repeat(cols.length)).append('\n');
    for (final Map<String, Object> row : rows) {
      sb.append('|');
      for (final String c : cols) {
        final Object v = row.get(c);
        sb.append(' ')
            .append(v instanceof Double d ? String.format(Locale.ROOT, "%.4f", d).replaceAll("\\.?0+$", "") : v)
            .append(" |");
      }
      sb.append('\n');
    }
    sb.append('\n');
    return sb.toString();
  }

  /** Rounded to four places; "n/a" when the volatility is zero. */
  private static Object ratio(final double v) {
    return Double.isNaN(v) ? "n/a" : Math.round(v * 1e4) / 1e4;
  }
}
