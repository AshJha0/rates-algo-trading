package io.ratesalgo.strategy.forward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.strategy.Support;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forward cycle on hand-written input files (made-up rates in the {@code forward_inputs/1}
 * layout; no network): decision then outcome order, the close-mark arithmetic, no look-ahead,
 * idempotence, holidays, gaps and tampering.
 */
class ForwardCycleTest {

  @TempDir Path dir;

  private static ForwardSettings settings() {
    final Path root = Support.repoRoot();
    return ForwardSettings.load(root, root.resolve("config/forward/desk.yaml"));
  }

  private void input(final LocalDate d, final double shiftBp) throws IOException {
    final double s = shiftBp / 100.0;
    final String json = String.format(Locale.ROOT, """
        {"schema": "forward_inputs/1", "as_of": "%s",
         "ust_par_pct": {"2Y": %.4f, "5Y": %.4f, "10Y": %.4f, "30Y": %.4f},
         "sofr": {"effective_date": "%s", "pct": 3.92, "revision": ""},
         "swaps_pct": {"2Y": {"rate": %.4f, "source": "dtcc_median", "prints": 9},
                       "5Y": {"rate": %.4f, "source": "dtcc_median", "prints": 9},
                       "10Y": {"rate": %.4f, "source": "dtcc_median", "prints": 9},
                       "30Y": {"rate": %.4f, "source": "ust_plus_spread", "spread_bp": -80.0, "spread_from": "config"}},
         "sources": []}
        """, d, 4.50 + s, 4.60 + s, 4.85 + s, 5.25 + s, d.minusDays(1), 4.36 + s, 4.35 + s, 4.45 + s, 4.45 + s);
    Files.createDirectories(dir.resolve("inputs"));
    Files.writeString(dir.resolve("inputs").resolve(d + ".json"), json, StandardCharsets.UTF_8);
  }

  private ForwardCycle cycle() {
    return new ForwardCycle(settings(), dir, "test");
  }

  @Test
  void firstDayDecidesThenNextDayClosesAndDecides() throws IOException {
    final LocalDate fri = LocalDate.of(2026, 11, 6);
    final LocalDate mon = LocalDate.of(2026, 11, 9);
    input(fri, 0);
    input(mon, 5);
    assertEquals(1, cycle().run(fri, dir.resolve("work")).appended().size());
    final ForwardCycle.Step step = cycle().run(mon, dir.resolve("work"));
    assertEquals(2, step.appended().size());
    final List<Map<String, Object>> e = cycle().entries();
    assertEquals(List.of("decision", "outcome", "decision"), e.stream().map(m -> m.get("kind")).toList());
    assertEquals("2026-11-09", e.get(0).get("session"));
    assertEquals("2026-11-10", e.get(2).get("session"));
    final Map<String, Object> o = e.get(1);
    assertEquals(1L, ((Number) o.get("closes_seq")).longValue());
    assertEquals("synthetic", o.get("flow"));
    // close mark = sum of position * (marked - committed) * value per unit, to a cent
    double mark = 0;
    final Map<String, Object> book = Yaml.asMap(o.get("book"), "book");
    for (final Instrument i : InstrumentSet.usdDefault().all()) {
      final Map<String, Object> l = Yaml.asMap(book.get(Integer.toString(i.id())), "line");
      mark += ((Number) l.get("position")).longValue()
          * (Yaml.toDouble(l.get("mid_marked"), "m") - Yaml.toDouble(l.get("mid_committed"), "c")) * i.valuePerUnit();
    }
    assertEquals(mark, Yaml.toDouble(o.get("close_mark"), "close_mark"), 0.01 * InstrumentSet.usdDefault().all().size());
    final Map<String, Object> s = Yaml.asMap(o.get("session_result"), "s");
    assertTrue(((Number) s.get("client_fills")).longValue() > 0, "the synthetic session trades");
    assertEquals(Yaml.toDouble(s.get("position_pnl"), "p") + Yaml.toDouble(o.get("close_mark"), "c"),
        Yaml.toDouble(o.get("pnl_total"), "t"), 0.02);
    assertTrue(Files.isRegularFile(dir.resolve("reports/2026-11-09.md")));
    assertTrue(Files.readString(dir.resolve("reports/SUMMARY.md")).contains("No ratio is printed"));
  }

  @Test
  void decisionDoesNotDependOnLaterData() throws IOException {
    final LocalDate fri = LocalDate.of(2026, 11, 6);
    input(fri, 0);
    final String alone = cycle().run(fri, dir.resolve("work")).appended().get(0);
    Files.delete(dir.resolve("ledger.jsonl"));
    input(LocalDate.of(2026, 11, 9), 25);
    input(LocalDate.of(2026, 11, 10), -40);
    assertEquals(alone, cycle().run(fri, dir.resolve("work")).appended().get(0));
  }

  @Test
  void sameSessionIsReproducibleAndSeedFollowsTheChain() throws IOException {
    final LocalDate fri = LocalDate.of(2026, 11, 6);
    input(fri, 0);
    input(LocalDate.of(2026, 11, 9), 3);
    cycle().run(fri, dir.resolve("work"));
    final String outcome1 = cycle().run(LocalDate.of(2026, 11, 9), dir.resolve("work")).appended().get(0);
    final List<Map<String, Object>> e = cycle().entries();
    assertNotEquals(e.get(0).get("seed"), e.get(2).get("seed"));
    Files.writeString(dir.resolve("ledger.jsonl"),
        Files.readAllLines(dir.resolve("ledger.jsonl")).get(0) + "\n", StandardCharsets.UTF_8);
    assertEquals(outcome1, cycle().run(LocalDate.of(2026, 11, 9), dir.resolve("work")).appended().get(0));
  }

  @Test
  void rerunningTheSameDayAddsNothing() throws IOException {
    final LocalDate fri = LocalDate.of(2026, 11, 6);
    input(fri, 0);
    cycle().run(fri, dir.resolve("work"));
    assertEquals(0, cycle().run(fri, dir.resolve("work")).appended().size());
    assertEquals(1, cycle().entries().size());
  }

  @Test
  void holidaySessionIsMarkedAtTheNextBusinessDayAndLongGapsAreSkipped() throws IOException {
    final LocalDate wed = LocalDate.of(2026, 11, 25);
    final LocalDate fri = LocalDate.of(2026, 11, 27); // Thursday 26th is a holiday
    input(wed, 0);
    input(fri, 2);
    cycle().run(wed, dir.resolve("work"));
    cycle().run(fri, dir.resolve("work"));
    final Map<String, Object> o = cycle().entries().get(1);
    assertEquals("2026-11-26", o.get("session"));
    assertEquals("2026-11-27", o.get("marked_on"));
    final LocalDate late = LocalDate.of(2026, 12, 14);
    input(late, 0);
    cycle().run(late, dir.resolve("work"));
    final List<Map<String, Object>> e = cycle().entries();
    assertEquals("skipped", e.get(3).get("kind"));
    assertEquals("decision", e.get(4).get("kind"));
  }

  @Test
  void editingAPastLineBreaksTheChain() throws IOException {
    final LocalDate fri = LocalDate.of(2026, 11, 6);
    input(fri, 0);
    input(LocalDate.of(2026, 11, 9), 3);
    cycle().run(fri, dir.resolve("work"));
    cycle().run(LocalDate.of(2026, 11, 9), dir.resolve("work"));
    final Path ledger = dir.resolve("ledger.jsonl");
    Files.writeString(ledger, Files.readString(ledger).replaceFirst("\"seed\":\\d", "\"seed\":9"));
    assertThrows(IllegalStateException.class, () -> cycle().entries());
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final int code = ForwardCli.run(new String[] {"--repo", Support.repoRoot().toString(), "--forward-dir",
        dir.toString(), "--verify"}, new PrintStream(out, true, StandardCharsets.UTF_8));
    assertEquals(1, code);
  }
}
