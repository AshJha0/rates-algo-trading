package io.ratesalgo.strategy.forward;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Yaml;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One daily cycle of the forward log (docs/research/PAPER_TRADING_PLAN.md Section 3).
 *
 * <p>Plain idea: on each Treasury business day {@code D}, first close the open decision (run its
 * session with the committed seed on the committed mids, then mark the resulting book at {@code
 * D}'s real close), then write the decision for the next weekday from data dated {@code D} or
 * earlier only. Both go to an append-only ledger with the hash chain of the experiment ledger
 * ({@link Ledger}), so a past line cannot be changed without breaking every later one.
 *
 * <p>Ledger lines ({@code kind}):
 *
 * <ul>
 *   <li>{@code decision}: {@code decided_on}, {@code session}, the input file and its SHA-256, the
 *       settings, experiment config and presets hashes, the code commit, the committed mids by
 *       instrument id, SOFR and the flow seed. The seed is the first 60 bits of SHA-256 of the
 *       previous line and the session date, so it is fixed by the chain and cannot be picked.
 *   <li>{@code outcome}: {@code closes_seq}, {@code session}, {@code marked_on}, the session's
 *       attribution (synthetic flow at committed mids), the close mark {@code sum_i q_i (m_i(D) -
 *       m_i) v_i} of the end-of-session positions {@code q_i} (real), their DV01, and the total.
 *   <li>{@code skipped}: {@code closes_seq} and a reason, when the session went unmarked for more
 *       than {@code max_mark_gap_days}.
 * </ul>
 *
 * <p>A session falling on a holiday is marked at the next Treasury business day, with the same
 * committed decision; {@code marked_on} shows it. Running the cycle twice for the same day adds
 * nothing.
 */
public final class ForwardCycle {

  /** What a cycle did. */
  public record Step(List<String> appended, List<Path> reports) {
    public Step {
      appended = List.copyOf(appended);
      reports = List.copyOf(reports);
    }
  }

  private final ForwardSettings settings;
  private final Path forwardDir;
  private final String commit;
  private final Ledger ledger;
  private final Map<Integer, Double> couponPct;
  private final double futurePrice0;

  public ForwardCycle(final ForwardSettings settings, final Path forwardDir, final String commit) {
    this.settings = settings;
    this.forwardDir = forwardDir;
    this.commit = commit;
    this.ledger = new Ledger(forwardDir.resolve("ledger.jsonl"));
    final Map<String, Object> experiment = Yaml.loadMap(settings.experimentConfig());
    final Map<Integer, Double> c = new TreeMap<>();
    for (final Map.Entry<String, Object> e : Yaml.map(experiment, "carry", "experiment config").entrySet()) {
      c.put(Integer.parseInt(e.getKey()), Yaml.toDouble(e.getValue(), "experiment config carry"));
    }
    this.couponPct = Map.copyOf(c);
    this.futurePrice0 = Presets.load(settings.presetsFile()).get("usd_synthetic_market").value("future_price_0");
  }

  /** The ledger's lines, chain verified. */
  public List<Map<String, Object>> entries() {
    return ledger.entries();
  }

  /** The next Monday-to-Friday day after {@code d}; holidays are handled at marking time. */
  static LocalDate nextWeekday(final LocalDate d) {
    LocalDate n = d.plusDays(1);
    while (n.getDayOfWeek() == DayOfWeek.SATURDAY || n.getDayOfWeek() == DayOfWeek.SUNDAY) {
      n = n.plusDays(1);
    }
    return n;
  }

  /** Mids of the default instruments at the close of {@code inputs}. */
  Map<Integer, Double> mids(final ForwardInputs inputs) {
    return inputs.mids(InstrumentSet.usdDefault(), couponPct, futurePrice0, settings.futureRefYieldPct());
  }

  /** Runs the cycle for Treasury business day {@code asOf}, whose input file must exist. */
  public Step run(final LocalDate asOf, final Path workDir) {
    final List<Map<String, Object>> entries = ledger.entries();
    final ForwardInputs inputs = ForwardInputs.load(forwardDir.resolve("inputs"), asOf);
    final List<String> appended = new ArrayList<>();
    final List<Path> reports = new ArrayList<>();
    final Map<String, Object> last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
    if (last != null && "decision".equals(last.get("kind"))) {
      final LocalDate session = LocalDate.parse((String) last.get("session"));
      final LocalDate decidedOn = LocalDate.parse((String) last.get("decided_on"));
      if (!asOf.isAfter(decidedOn)) {
        return new Step(appended, reports); // already decided from this day's data
      }
      if (asOf.isBefore(session)) {
        throw new IllegalStateException("input day " + asOf + " falls before the open session " + session);
      }
      final long seq = ((Number) last.get("seq")).longValue();
      if (ChronoUnit.DAYS.between(session, asOf) > settings.maxMarkGapDays()) {
        final Map<String, Object> skip = new LinkedHashMap<>();
        skip.put("kind", "skipped");
        skip.put("closes_seq", seq);
        skip.put("session", session.toString());
        skip.put("reason", "no mark within " + settings.maxMarkGapDays() + " days of the session; next input " + asOf);
        appended.add(ledger.append(skip));
      } else {
        final Map<String, Object> outcome = outcome(last, seq, session, inputs, workDir);
        appended.add(ledger.append(outcome));
        reports.add(writeDayReport(outcome));
      }
    }
    appended.add(ledger.append(decision(asOf, inputs)));
    reports.add(ForwardReport.writeSummary(ledger.entries(), forwardDir.resolve("reports")));
    return new Step(appended, reports);
  }

  private String lastLineHash() {
    try {
      final Path f = ledger.file();
      if (!Files.exists(f)) {
        return Ledger.GENESIS;
      }
      final List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
      return lines.isEmpty() ? Ledger.GENESIS : Hashing.sha256Text(lines.get(lines.size() - 1));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The decision for the weekday after {@code asOf}; reads nothing dated after {@code asOf}. */
  Map<String, Object> decision(final LocalDate asOf, final ForwardInputs inputs) {
    final LocalDate session = nextWeekday(asOf);
    final long seed = Long.parseLong(Hashing.sha256Text(lastLineHash() + "|" + session).substring(0, 15), 16);
    final Map<String, Object> mids = new TreeMap<>();
    for (final Map.Entry<Integer, Double> e : mids(inputs).entrySet()) {
      mids.put(Integer.toString(e.getKey()), round(e.getValue(), 8));
    }
    final Map<String, Object> d = new LinkedHashMap<>();
    d.put("kind", "decision");
    d.put("decided_on", asOf.toString());
    d.put("session", session.toString());
    d.put("inputs_file", "inputs/" + inputs.file());
    d.put("inputs_sha256", inputs.sha256());
    d.putAll(settings.hashes());
    d.put("code_commit", commit);
    d.put("desk", "desk_default");
    d.put("band_point", settings.bandPoint().label());
    d.put("mids", mids);
    d.put("swap_mark_sources", new TreeMap<>(inputs.swapSource()));
    d.put("sofr_pct", inputs.sofrPct());
    d.put("seed", seed);
    d.put("flow", "synthetic");
    d.put("marks", "real");
    return d;
  }

  private Map<String, Object> outcome(
      final Map<String, Object> decision, final long seq, final LocalDate session, final ForwardInputs inputs,
      final Path workDir) {
    final Map<Integer, Double> committed = new TreeMap<>();
    for (final Map.Entry<String, Object> e : Yaml.map(decision, "mids", "decision").entrySet()) {
      committed.put(Integer.parseInt(e.getKey()), Yaml.toDouble(e.getValue(), "decision mids"));
    }
    final double sofr = Yaml.toDouble(decision.get("sofr_pct"), "decision sofr_pct");
    final long seed = ((Number) decision.get("seed")).longValue();
    final ForwardSession.Result r = new ForwardSession(settings).run(session, seed, committed, sofr, workDir);
    final Map<Integer, Double> marks = mids(inputs);
    final InstrumentSet instruments = InstrumentSet.usdDefault();
    double closeMark = 0;
    double dv01 = 0;
    final Map<String, Object> book = new TreeMap<>();
    for (final Instrument i : instruments.all()) {
      final long q = r.endPositions().getOrDefault(i.id(), 0L);
      final double from = committed.get(i.id());
      final double to = marks.get(i.id());
      final double pnl = q * (to - from) * i.valuePerUnit();
      closeMark += pnl;
      dv01 += q * i.dv01PerUnit();
      final Map<String, Object> line = new LinkedHashMap<>();
      line.put("position", q);
      line.put("mid_committed", round(from, 8));
      line.put("mid_marked", round(to, 8));
      line.put("close_mark", round(pnl, 2));
      line.put("dv01", round(q * i.dv01PerUnit(), 2));
      book.put(Integer.toString(i.id()), line);
    }
    final Attribution a = r.backtest().total();
    final Map<String, Object> s = new LinkedHashMap<>();
    for (final Map.Entry<String, Object> e : a.asMap().entrySet()) {
      s.put(e.getKey(), round((Double) e.getValue(), 2));
    }
    s.put("rfqs", (long) r.backtest().rfqs().size());
    s.put("declines", r.backtest().declines());
    s.put("client_fills", r.backtest().clientFills());
    s.put("hedge_fills", r.backtest().hedgeFills());
    s.put("max_abs_net_dv01", round(r.backtest().maxAbsNetDv01(), 2));
    s.put("output_chain_hash", Long.toHexString(r.backtest().outputChainHash()));
    final Map<String, Object> o = new LinkedHashMap<>();
    o.put("kind", "outcome");
    o.put("closes_seq", seq);
    o.put("session", session.toString());
    o.put("marked_on", inputs.asOf().toString());
    o.put("inputs_file", "inputs/" + inputs.file());
    o.put("inputs_sha256", inputs.sha256());
    o.put("code_commit", commit);
    o.put("session_result", s);
    o.put("book", book);
    o.put("close_mark", round(closeMark, 2));
    o.put("end_net_dv01", round(dv01, 2));
    o.put("pnl_total", round(a.positionPnl() + closeMark, 2));
    o.put("flow", "synthetic");
    o.put("marks", "real");
    return o;
  }

  private Path writeDayReport(final Map<String, Object> o) {
    final Path dir = forwardDir.resolve("reports");
    try {
      Files.createDirectories(dir);
      final Path p = dir.resolve(o.get("session") + ".md");
      Files.writeString(p, ForwardReport.day(o), StandardCharsets.UTF_8);
      return p;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static double round(final double v, final int places) {
    final double f = Math.pow(10, places);
    final double r = Math.rint(v * f) / f;
    return r == 0.0 ? 0.0 : r; // no negative zero in the ledger
  }

  static String money(final double v) {
    return String.format(Locale.ROOT, "%,.2f", v);
  }
}
