package io.ratesalgo.strategy.forward;

import io.ratesalgo.backtest.util.Yaml;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Markdown reports of the forward log: one per marked session and a rolling {@code SUMMARY.md}.
 * Both are rebuilt from ledger lines only, so anyone can regenerate them from the ledger.
 */
public final class ForwardReport {

  /** No ratio of mean to standard deviation is printed before this many marked sessions. */
  public static final int MIN_DAYS_FOR_RATIO = 60;

  private static final String LABEL =
      "Client RFQs, fills and hedge fills are synthetic (fitted models). Rates and the close mark are real "
          + "(US Treasury par curve, NY Fed SOFR, DTCC SOFR OIS prints). See docs/research/PAPER_TRADING_PLAN.md.";

  private ForwardReport() {}

  private static double num(final Map<String, Object> m, final String key) {
    return Yaml.toDouble(m.get(key), key);
  }

  /** The report of one outcome line. */
  public static String day(final Map<String, Object> o) {
    final Map<String, Object> s = Yaml.asMap(o.get("session_result"), "session_result");
    final StringBuilder b = new StringBuilder(2048);
    b.append("# Forward session ").append(o.get("session")).append("\n\n");
    b.append(LABEL).append("\n\n");
    b.append("Marked on ").append(o.get("marked_on")).append(" (input ").append(o.get("inputs_file"))
        .append(", SHA-256 ").append(o.get("inputs_sha256")).append(").\n\n");
    b.append("| Line | USD |\n|---|---:|\n");
    for (final String k : new String[] {"spread_capture", "hedge_cost", "inventory_mtm", "carry_roll", "funding", "fees"}) {
      b.append("| ").append(k).append(" (session, synthetic flow) | ").append(ForwardCycle.money(num(s, k))).append(" |\n");
    }
    b.append("| session P&L | ").append(ForwardCycle.money(num(s, "position_pnl"))).append(" |\n");
    b.append("| close mark (real move) | ").append(ForwardCycle.money(num(o, "close_mark"))).append(" |\n");
    b.append("| **total** | **").append(ForwardCycle.money(num(o, "pnl_total"))).append("** |\n\n");
    b.append(String.format(Locale.ROOT, "RFQs %d, declined %d, client fills %d, hedge fills %d; max |net DV01| %s; "
            + "end net DV01 %s USD/bp.%n%n",
        ((Number) s.get("rfqs")).longValue(), ((Number) s.get("declines")).longValue(),
        ((Number) s.get("client_fills")).longValue(), ((Number) s.get("hedge_fills")).longValue(),
        ForwardCycle.money(num(s, "max_abs_net_dv01")), ForwardCycle.money(num(o, "end_net_dv01"))));
    b.append("| Instrument | Position | Committed mid | Marked mid | Close mark | DV01 |\n|---|---:|---:|---:|---:|---:|\n");
    for (final Map.Entry<String, Object> e : Yaml.asMap(o.get("book"), "book").entrySet()) {
      final Map<String, Object> l = Yaml.asMap(e.getValue(), "book line");
      b.append(String.format(Locale.ROOT, "| %s | %d | %.6f | %.6f | %s | %s |%n", e.getKey(),
          ((Number) l.get("position")).longValue(), num(l, "mid_committed"), num(l, "mid_marked"),
          ForwardCycle.money(num(l, "close_mark")), ForwardCycle.money(num(l, "dv01"))));
    }
    return b.toString();
  }

  /** Rebuilds {@code SUMMARY.md} in {@code dir} from every ledger line. */
  public static Path writeSummary(final List<Map<String, Object>> entries, final Path dir) {
    final StringBuilder rows = new StringBuilder(4096);
    int days = 0;
    int skipped = 0;
    int decisions = 0;
    double cum = 0;
    double peak = 0;
    double maxDd = 0;
    double sum = 0;
    double sumSq = 0;
    for (final Map<String, Object> e : entries) {
      final Object kind = e.get("kind");
      if ("decision".equals(kind)) {
        decisions++;
      } else if ("skipped".equals(kind)) {
        skipped++;
        rows.append("| ").append(e.get("session")).append(" | skipped: ").append(e.get("reason")).append(" | | | | | |\n");
      } else if ("outcome".equals(kind)) {
        days++;
        final Map<String, Object> s = Yaml.asMap(e.get("session_result"), "session_result");
        final double total = num(e, "pnl_total");
        cum += total;
        peak = Math.max(peak, cum);
        maxDd = Math.max(maxDd, peak - cum);
        sum += total;
        sumSq += total * total;
        rows.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s |%n", e.get("session"),
            e.get("marked_on"), ForwardCycle.money(num(s, "position_pnl")), ForwardCycle.money(num(e, "close_mark")),
            ForwardCycle.money(total), ForwardCycle.money(cum), ForwardCycle.money(num(e, "end_net_dv01"))));
      }
    }
    final StringBuilder b = new StringBuilder(4096 + rows.length());
    b.append("# Forward log summary\n\n").append(LABEL).append("\n\n");
    b.append(String.format(Locale.ROOT, "Decisions %d, marked sessions %d, skipped %d. Cumulative P&L %s USD, "
        + "largest drawdown %s USD.%n%n", decisions, days, skipped, ForwardCycle.money(cum), ForwardCycle.money(maxDd)));
    if (days >= 2) {
      final double mean = sum / days;
      final double sd = Math.sqrt(Math.max(0, (sumSq - days * mean * mean) / (days - 1)));
      b.append(String.format(Locale.ROOT, "Mean daily P&L %s USD, standard error %s USD (%d sessions).", ForwardCycle.money(mean),
          ForwardCycle.money(sd / Math.sqrt(days)), days));
      if (days >= MIN_DAYS_FOR_RATIO && sd > 0) {
        final double ratio = mean / sd * Math.sqrt(252);
        b.append(String.format(Locale.ROOT, " Annualised mean over standard deviation %.2f, standard error about %.2f.",
            ratio, Math.sqrt((1 + ratio * ratio / 2 / 252) * 252.0 / days)));
      } else {
        b.append(" No ratio is printed before ").append(MIN_DAYS_FOR_RATIO).append(" marked sessions.");
      }
      b.append("\n\n");
    } else {
      b.append("No ratio is printed before ").append(MIN_DAYS_FOR_RATIO).append(" marked sessions.\n\n");
    }
    b.append("| Session | Marked on | Session P&L | Close mark | Total | Cumulative | End net DV01 |\n");
    b.append("|---|---|---:|---:|---:|---:|---:|\n").append(rows);
    try {
      Files.createDirectories(dir);
      final Path p = dir.resolve("SUMMARY.md");
      Files.writeString(p, b.toString(), StandardCharsets.UTF_8);
      return p;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
