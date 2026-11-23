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
 *
 * <p>The summary keeps the two parts of the P&amp;L apart. The session P&amp;L is income from
 * synthetic client flow against a market with its intraday moves switched off, so it carries
 * almost no market risk and its mean is many times its day-to-day variation by construction. A
 * mean over its standard deviation would be very large and would say nothing about a real desk,
 * so no such ratio is printed, at any sample size. The close mark is
 * the one real number: the end-of-session position times the real move to the marking close. It
 * is reported with its mean and standard error and nothing else. Its expected value is zero (the
 * desk takes no view on direction), so it measures the exposure left overnight, not skill.
 */
public final class ForwardReport {

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
    b.append("| session P&L (synthetic flow) | ").append(ForwardCycle.money(num(s, "position_pnl"))).append(" |\n");
    b.append("| close mark (real move) | ").append(ForwardCycle.money(num(o, "close_mark"))).append(" |\n");
    b.append("| total (synthetic session P&L + real close mark) | ").append(ForwardCycle.money(num(o, "pnl_total")))
        .append(" |\n\n");
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

  /** The text of {@code SUMMARY.md} for {@code entries}, every ledger line in order. */
  public static String summary(final List<Map<String, Object>> entries) {
    final StringBuilder rows = new StringBuilder(4096);
    int days = 0;
    int skipped = 0;
    int decisions = 0;
    double cumSession = 0;
    double cumMark = 0;
    double sumSqMark = 0;
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
        final double session = num(s, "position_pnl");
        final double mark = num(e, "close_mark");
        cumSession += session;
        cumMark += mark;
        sumSqMark += mark * mark;
        rows.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s |%n", e.get("session"),
            e.get("marked_on"), ForwardCycle.money(session), ForwardCycle.money(cumSession), ForwardCycle.money(mark),
            ForwardCycle.money(cumMark), ForwardCycle.money(num(e, "end_net_dv01"))));
      }
    }
    final StringBuilder b = new StringBuilder(4096 + rows.length());
    b.append("# Forward log summary\n\n").append(LABEL).append("\n\n");
    b.append(String.format(Locale.ROOT, "Decisions %d, marked sessions %d, skipped %d.%n%n", decisions, days, skipped));
    b.append("## Real component: close mark on the residual position\n\n");
    b.append("The end-of-session position times the real move from the committed close to the marking close. ");
    b.append("The desk takes no view on direction, so the expected value is zero: this line measures the exposure ");
    b.append("left overnight, not skill.\n\n");
    b.append(String.format(Locale.ROOT, "Cumulative %s USD over %d sessions.", ForwardCycle.money(cumMark), days));
    if (days >= 2) {
      final double mean = cumMark / days;
      final double sd = Math.sqrt(Math.max(0, (sumSqMark - days * mean * mean) / (days - 1)));
      b.append(String.format(Locale.ROOT, " Mean %s USD a session, standard error %s USD.", ForwardCycle.money(mean),
          ForwardCycle.money(sd / Math.sqrt(days))));
    } else {
      b.append(" A mean and standard error need two marked sessions.");
    }
    b.append("\n\n## Synthetic component: session P&L\n\n");
    b.append("Synthetic. Spread income, hedge cost, carry, funding and fees from model-generated client flow against ");
    b.append("a market with no intraday moves. With the market held still the income carries almost no market risk, ");
    b.append("so its mean is many times its day-to-day variation by construction. That is a property of the set-up, ");
    b.append("not a track record, and no ratio of mean to standard deviation is printed for it or for any total that ");
    b.append("contains it.\n\n");
    b.append(String.format(Locale.ROOT, "Cumulative %s USD (synthetic) over %d sessions.%n%n", ForwardCycle.money(cumSession),
        days));
    b.append("| Session | Marked on | Session P&L (synthetic) | Cumulative (synthetic) | Close mark (real) "
        + "| Cumulative close mark (real) | End net DV01 |\n");
    b.append("|---|---|---:|---:|---:|---:|---:|\n").append(rows);
    return b.toString();
  }

  /** Rebuilds {@code SUMMARY.md} in {@code dir} from every ledger line. */
  public static Path writeSummary(final List<Map<String, Object>> entries, final Path dir) {
    try {
      Files.createDirectories(dir);
      final Path p = dir.resolve("SUMMARY.md");
      Files.writeString(p, summary(entries), StandardCharsets.UTF_8);
      return p;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
