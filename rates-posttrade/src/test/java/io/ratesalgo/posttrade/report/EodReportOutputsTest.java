package io.ratesalgo.posttrade.report;

import static io.ratesalgo.posttrade.Fixtures.NINE;
import static io.ratesalgo.posttrade.Fixtures.px;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.posttrade.Fixtures;
import io.ratesalgo.posttrade.chaos.JournalScript;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The end-of-day run's files and messages, line by line, on a small journal built for the purpose. */
class EodReportOutputsTest {

  private static final long DAY = 86_400_000_000_000L;

  @TempDir Path dir;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private int run(final String... args) {
    return EodReportMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  private String[] args(final Path journal, final String date, final Path outDir) {
    return new String[] {"--journal", journal.toString(), "--date", date,
        "--instruments", Fixtures.path("instruments.csv").toString(),
        "--books", Fixtures.path("books.csv").toString(),
        "--marks", Fixtures.path("marks.csv").toString(),
        "--carry", Fixtures.path("carry.csv").toString(),
        "--out", outDir.toString()};
  }

  @Test
  void theFilesHoldTheDaysPnlRecordsAlertsAndMessageCounts() throws Exception {
    final Path j = dir.resolve("day.raj");
    try (JournalScript s = new JournalScript(j, 1 << 16, SyntheticDay.MIDNIGHT)) {
      s.newOrder(NINE, 1, 7, 1001, Side.Buy, px("99.5"), 300);
      s.executionReport(NINE + 1, 1, 7, 507, 71, 1001, Side.Buy, OrdStatus.Filled, px("99.5"), 300, 0, 300, NINE + 1);
      s.executionReport(NINE + 2, 1, 8, 508, 72, 1001, Side.Sell, OrdStatus.Filled, px("99.5"), 100, 0, 100, NINE + 2);
      s.newOrder(NINE + DAY, 1, 9, 1001, Side.Buy, px("99.5"), 100); // the next day: not in this archive
    }
    final Path outDir = dir.resolve("out");
    assertEquals(0, run(args(j, "2026-10-30", outDir)), err.toString(StandardCharsets.UTF_8));

    final List<String> pnl = Files.readAllLines(outDir.resolve("official-pnl-2026-10-30.csv"));
    assertTrue(pnl.get(0).startsWith("date,book,"));
    assertTrue(pnl.size() > 1);
    for (final String line : pnl.subList(1, pnl.size())) {
      assertTrue(line.startsWith("2026-10-30,1,"), line);
    }
    final List<String> records = Files.readAllLines(outDir.resolve("records/2026/10/30/order-records.csv"));
    assertEquals(3, records.stream().filter(l -> l.contains(",2026-10-30,")).count(), String.join("\n", records));
    assertTrue(records.stream().noneMatch(l -> l.contains("2026-10-31")), "the next day's order is not archived");
    final List<String> alerts = Files.readAllLines(outDir.resolve("surveillance-2026-10-30.csv"));
    assertEquals(List.of("detector,instrument_id,journal_sequence,timestamp_nanos,detail"), alerts.subList(0, 1));
    assertEquals(2, alerts.size(), String.join("\n", alerts));
    assertTrue(alerts.get(1).startsWith("self-trade,1001,3," + (NINE + 2) + ",venue 1 exec 71 Buy and exec 72 Sell"),
        alerts.get(1));
    assertEquals(List.of("instrument_id,messages,cancels", "1001,2,0"),
        Files.readAllLines(outDir.resolve("messages-2026-10-30.csv")));
    assertEquals("eod 2026-10-30: positions=1 records=4 alerts=1", out.toString(StandardCharsets.UTF_8).strip());
  }

  @Test
  void everyFailureSaysWhatWentWrong() {
    assertEquals(2, run("journal", "x"));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("unexpected argument journal"));
    err.reset();
    assertEquals(2, run("--journal", "x", "--date", "2026-10-30"));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("missing --instruments"), err.toString(StandardCharsets.UTF_8));
    err.reset();
    assertEquals(2, run(args(dir.resolve("missing.raj"), "not-a-date", dir.resolve("o"))));
    assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("error: "), err.toString(StandardCharsets.UTF_8));
    err.reset();
    final String[] a = args(dir.resolve("missing.raj"), "2026-10-30", dir.resolve("o"));
    a[5] = dir.resolve("missing.csv").toString();
    assertEquals(2, run(a));
    assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("io error: "), err.toString(StandardCharsets.UTF_8));
    assertEquals("", out.toString(StandardCharsets.UTF_8));
  }
}
