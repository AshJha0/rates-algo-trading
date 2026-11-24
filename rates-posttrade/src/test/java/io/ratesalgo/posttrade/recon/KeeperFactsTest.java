package io.ratesalgo.posttrade.recon;

import static io.ratesalgo.posttrade.Fixtures.NINE;
import static io.ratesalgo.posttrade.Fixtures.px;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.AmendReason;
import io.ratesalgo.messages.sbe.BustReason;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.Fixtures;
import io.ratesalgo.posttrade.chaos.JournalScript;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import io.ratesalgo.posttrade.position.PositionKeeper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the journal explains about a break (KeeperFacts class comment), at the exact cut sequence
 * boundaries, and the daily run's position and pending-bust reconciliations with their figures.
 */
class KeeperFactsTest {

  @TempDir Path dir;

  private Path journal(final Consumer<JournalScript> script) {
    final Path j = dir.resolve("j" + System.nanoTime() + ".raj");
    try (JournalScript s = new JournalScript(j, 1 << 16, SyntheticDay.MIDNIGHT)) {
      script.accept(s);
    }
    return j;
  }

  private static PositionKeeper keeper(final Path j, final long upTo) {
    final PositionKeeper k = new PositionKeeper(Fixtures.books(), Fixtures.catalogue());
    new Replayer(new ManualClock(0)).replay(j, (seq, at, t, v, b, o, l) -> {
      if (seq <= upTo) {
        k.onFrame(seq, at, t, v, b, o, l);
      }
    });
    return k;
  }

  private static void fill(final JournalScript s, final long at, final long execId, final Side side, final long qty) {
    s.executionReport(at, 1, execId, 500 + execId, execId, 1001, side, OrdStatus.Filled, px("100"), qty, 0, qty, at);
  }

  @Test
  void eachBreakClassIsExplainedOnlyByTheFactThatCoversIt() {
    final PositionKeeper full = keeper(journal(s -> {
      fill(s, NINE, 1, Side.Buy, 100); // seq 1
      fill(s, NINE + 1, 2, Side.Buy, 100); // seq 2
      s.bust(NINE + 2, 1, TradeSource.Execution, 1, 501, 1001, BustReason.VenueError); // seq 3
      fill(s, NINE + 3, 3, Side.Buy, 100); // seq 4
      s.bust(NINE + 4, 1, TradeSource.Execution, 3, 503, 1001, BustReason.ClearingRejected); // seq 5
      s.amend(NINE + 5, 1, TradeSource.Execution, 2, px("100.5"), 100, 7, AmendReason.PriceCorrection); // seq 6
      fill(s, NINE + 6, 4, Side.Buy, 100); // seq 7
      s.executionReport(NINE + 7, 1, 4, 504, 4, 1001, Side.Buy, OrdStatus.Filled, px("99"), 100, 0, 100, NINE + 6);
    }), Long.MAX_VALUE);

    final KeeperFacts atTwo = new KeeperFacts(full, 2);
    assertEquals(Explanation.NONE, atTwo.explain("1:E:2", BreakClass.MISSING_OURS), "booked at 2, the cut: not late");
    assertEquals(Explanation.NONE, atTwo.explain("1:E:1", BreakClass.MISSING_OURS), "busted, not late");
    final KeeperFacts atOne = new KeeperFacts(full, 1);
    assertEquals(Explanation.LATE_ARRIVAL, atOne.explain("1:E:2", BreakClass.MISSING_OURS));
    assertEquals(Explanation.CLEARING_REJECTED, atTwo.explain("1:E:3", BreakClass.MISSING_OURS));
    assertEquals(Explanation.NONE, atTwo.explain("1:E:9", BreakClass.MISSING_OURS), "never seen");

    assertEquals(Explanation.NONE, new KeeperFacts(full, 3).explain("1:E:1", BreakClass.MISSING_THEIRS),
        "busted at 3, the cut: inside the snapshot");
    assertEquals(Explanation.BUSTED_AFTER_CUT, atTwo.explain("1:E:1", BreakClass.MISSING_THEIRS));
    assertEquals(Explanation.NONE, atTwo.explain("1:E:2", BreakClass.MISSING_THEIRS), "not busted");

    assertEquals(Explanation.AMENDED, atTwo.explain("1:E:2", BreakClass.PRICE));
    assertEquals(Explanation.AMENDED, atTwo.explain("1:E:2", BreakClass.QUANTITY));
    assertEquals(Explanation.NONE, atTwo.explain("1:E:1", BreakClass.PRICE), "version 1");
    assertEquals(Explanation.NONE, atTwo.explain("1:E:9", BreakClass.QUANTITY));
    assertEquals(Explanation.NONE, atTwo.explain("1:E:2", BreakClass.TIMING));

    assertEquals(Explanation.DUPLICATE_CONFLICT, atTwo.explain("1:E:4", BreakClass.PRICE), "a conflicting repeat");
    assertEquals(Explanation.NONE, atTwo.explain("instrument 1001", BreakClass.QUANTITY), "not a trade key");
  }

  @Test
  void theDailyRunReportsPositionsByInstrumentAndPendingBusts() {
    final Path j = journal(s -> {
      fill(s, NINE, 1, Side.Buy, 100); // seq 1
      s.bust(NINE + 1, 1, TradeSource.Execution, 2, 502, 1001, BustReason.VenueError); // seq 2, before its fill
      fill(s, NINE + 2, 3, Side.Sell, 30); // seq 3
    });
    final PositionKeeper atCut = keeper(j, Long.MAX_VALUE);
    final List<ReconItem> dropCopy = List.of(
        new ReconItem("1:E:1", 1001, Side.Buy, 100, px("100"), NINE),
        new ReconItem("1:E:3", 1001, Side.Sell, 40, px("100"), NINE + 2));
    final BreakReport report = DailyReconciliation.run(new DailyReconciliation.Inputs(
        LocalDate.of(2026, 10, 30), atCut, atCut, 3, dropCopy, atCut.orderRecords(), List.of(), i -> false));
    assertEquals(List.of("drop-copy", "drop-copy-pos", "books-records", "clearing", "pending-busts"),
        report.results().stream().map(ReconResult::name).toList());
    final ReconResult trades = report.results().get(0);
    assertEquals(1, trades.breaks().size());
    assertEquals(BreakClass.QUANTITY, trades.breaks().get(0).breakClass());
    final ReconResult positions = report.results().get(1);
    assertEquals(1, positions.breaks().size());
    final Break pos = positions.breaks().get(0);
    assertEquals("instrument 1001", pos.matchKey());
    assertEquals("70", pos.ours(), "bought 100, sold 30");
    assertEquals("60", pos.theirs(), "bought 100, sold 40");
    assertEquals(0, report.results().get(2).breaks().size(), "books and records agree with the keeper");
    final ReconResult pending = report.results().get(4);
    assertEquals(1, pending.breaks().size());
    final Break b = pending.breaks().get(0);
    assertEquals("1:E:2", b.matchKey());
    assertEquals(BreakClass.MISSING_OURS, b.breakClass());
    assertEquals("bust without fill", b.theirs());
    assertEquals(1, pending.theirsCount());
  }
}
