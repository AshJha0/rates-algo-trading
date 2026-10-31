package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.journal.Replayer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Determinism test (P5 gate): the back-tester reproduces a synthetic day's P&amp;L from its
 * journal to the cent on a second run, with the same output hash and the same tables.
 */
class DeterminismTest {

  @TempDir Path dir;

  @Test
  @DisplayName("determinism: a second run from the same journal reproduces P&L to the cent")
  void secondRunReproducesPnlToTheCent() throws IOException {
    final Path journal = TestSupport.day(dir, LocalDate.of(2026, 2, 2));
    final BacktestConfig base = TestSupport.config(journal, BandPoint.CENTRAL);
    final BacktestResult first =
        new BacktestRunner(base.withOutputDir(dir.resolve("run1"))).run(TestSupport.fixed());
    final BacktestResult second =
        new BacktestRunner(base.withOutputDir(dir.resolve("run2"))).run(TestSupport.fixed());
    ResultsWriter.write(first, base.instruments(), dir.resolve("run1"));
    ResultsWriter.write(second, base.instruments(), dir.resolve("run2"));

    assertTrue(first.clientFills() > 50, "client fills: " + first.clientFills());
    assertEquals(
        Math.round(first.total().positionPnl() * 100), Math.round(second.total().positionPnl() * 100));
    assertEquals(first.outputChainHash(), second.outputChainHash());
    assertEquals(first.inputChainHash(), second.inputChainHash());
    for (final String table : new String[] {"fills.csv", "rfqs.csv", "pnl.csv", "summary.json"}) {
      assertEquals(
          Files.readString(dir.resolve("run1").resolve(table), StandardCharsets.UTF_8),
          Files.readString(dir.resolve("run2").resolve(table), StandardCharsets.UTF_8),
          table);
    }
    assertEquals(
        Replayer.finalHash(dir.resolve("run1/results.raj")),
        Replayer.finalHash(dir.resolve("run2/results.raj")));
  }

  @Test
  @DisplayName("determinism: regenerating the journal from its date and seed gives the same bytes")
  void journalIsReproducible() throws IOException {
    final Path a = TestSupport.day(Files.createDirectories(dir.resolve("a")), LocalDate.of(2026, 2, 3));
    final Path b = TestSupport.day(Files.createDirectories(dir.resolve("b")), LocalDate.of(2026, 2, 3));
    assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
  }

  @Test
  void journalsReplayedAsSegmentsGiveTheSameStreamAsOne() {
    final Path one = TestSupport.day(dir, LocalDate.of(2026, 2, 5));
    final Path two = TestSupport.day(dir, LocalDate.of(2026, 2, 6));
    final BacktestConfig both =
        BacktestConfig.fromPresets(
            TestSupport.presets(), java.util.List.of(one, two), 7L, BandPoint.CENTRAL, null);
    final BacktestResult r = new BacktestRunner(both).run(TestSupport.fixed());
    final BacktestResult a = new BacktestRunner(TestSupport.config(one, BandPoint.CENTRAL)).run(TestSupport.fixed());
    assertTrue(r.inputFrames() > a.inputFrames());
    assertTrue(r.rfqs().size() > a.rfqs().size());
    assertTrue(Math.abs(r.total().residual()) < 0.01);
  }
}
