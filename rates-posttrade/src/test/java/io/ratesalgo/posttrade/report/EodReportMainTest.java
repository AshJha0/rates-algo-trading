package io.ratesalgo.posttrade.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.posttrade.Fixtures;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EodReportMainTest {

  @TempDir Path dir;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private int run(final String... args) {
    return EodReportMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @Test
  void writesOfficialPnlRecordsAndAlerts() throws Exception {
    final SyntheticDay.Result day =
        SyntheticDay.generate(dir.resolve("day.raj"), 3L, SyntheticDay.Params.DEFAULT);
    final Path outDir = dir.resolve("out");
    final int status = run("--journal", day.journal().toString(), "--date", "2026-10-30",
        "--instruments", Fixtures.path("instruments.csv").toString(),
        "--books", Fixtures.path("books.csv").toString(),
        "--marks", Fixtures.path("marks.csv").toString(),
        "--carry", Fixtures.path("carry.csv").toString(),
        "--out", outDir.toString());
    assertEquals(0, status, err.toString(StandardCharsets.UTF_8));
    assertTrue(Files.readString(outDir.resolve("official-pnl-2026-10-30.csv")).startsWith("date,book,"));
    assertTrue(Files.exists(outDir.resolve("records/2026/10/30/order-records.csv")));
    assertTrue(Files.exists(outDir.resolve("surveillance-2026-10-30.csv")));
    final String messages = Files.readString(outDir.resolve("messages-2026-10-30.csv"));
    assertTrue(messages.startsWith("instrument_id,messages,cancels\n"), messages);
    assertTrue(messages.lines().count() > 1, "a synthetic day sends messages");
    assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("eod 2026-10-30: positions=3"));
  }

  @Test
  void badArgumentsAndMissingMarks() throws Exception {
    assertEquals(2, run("journal", "x"));
    assertEquals(2, run("--journal", "x"));
    final SyntheticDay.Result day =
        SyntheticDay.generate(dir.resolve("day2.raj"), 3L, SyntheticDay.Params.DEFAULT);
    final Path emptyMarks = dir.resolve("marks.csv");
    Files.writeString(emptyMarks, "date,instrument_id,mark\n");
    assertEquals(3, run("--journal", day.journal().toString(), "--date", "2026-10-30",
        "--instruments", Fixtures.path("instruments.csv").toString(),
        "--books", Fixtures.path("books.csv").toString(), "--marks", emptyMarks.toString(),
        "--carry", Fixtures.path("carry.csv").toString(), "--out", dir.resolve("o").toString()));
    final Path noDefault = dir.resolve("books.csv");
    Files.writeString(noDefault, "instrument_id,book,account,algorithm_id\n1001,1,1,A\n");
    assertEquals(2, run("--journal", day.journal().toString(), "--date", "2026-10-30",
        "--instruments", Fixtures.path("instruments.csv").toString(), "--books", noDefault.toString(),
        "--marks", emptyMarks.toString(), "--carry", Fixtures.path("carry.csv").toString(),
        "--out", dir.resolve("o").toString()));
    assertEquals(2, run("--journal", dir.resolve("missing.raj").toString(), "--date", "2026-10-30",
        "--instruments", dir.resolve("missing.csv").toString(), "--books", noDefault.toString(),
        "--marks", emptyMarks.toString(), "--carry", emptyMarks.toString(), "--out", dir.toString()));
  }
}
