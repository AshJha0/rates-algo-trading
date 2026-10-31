package io.ratesalgo.cookbook;

import io.ratesalgo.journal.JournalReader;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import io.ratesalgo.posttrade.clearing.ClearingHouseSimulator;
import io.ratesalgo.posttrade.position.InstrumentCatalogue;
import io.ratesalgo.posttrade.position.InstrumentReference;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.recon.BreakReport;
import io.ratesalgo.posttrade.recon.DailyReconciliation;
import io.ratesalgo.posttrade.trade.BookAssignment;
import io.ratesalgo.posttrade.trade.FixedBookMapper;
import io.ratesalgo.posttrade.trade.Trade;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

/** COOKBOOK.md recipe 21: run the chaos day and read the break report. */
public final class Recipe21ChaosBreakReport {

  private Recipe21ChaosBreakReport() {}

  static PositionKeeper keeper(final Path journal, final long lastSequence) {
    final InstrumentCatalogue catalogue = new InstrumentCatalogue()
        .add(new InstrumentReference(1001, "US91282CLF0X", "USD", "PERC", BigDecimal.ONE))
        .add(new InstrumentReference(1002, "US91282CLG0X", "USD", "PERC", BigDecimal.ONE))
        .add(new InstrumentReference(2001, "USD-SOFR-10Y", "USD", "PERC", new BigDecimal("8.5")));
    final FixedBookMapper books = new FixedBookMapper(new BookAssignment(1, 10, "ALG-UST-MM-01"))
        .with(2001, new BookAssignment(2, 20, "ALG-SWP-RFQ-01"));
    final PositionKeeper k = new PositionKeeper(books, catalogue);
    try (JournalReader r = JournalReader.open(journal)) {
      while (r.next((s, t, id, v, b, o, l) -> {
        if (s <= lastSequence) {
          k.onFrame(s, t, id, v, b, o, l);
        }
      })) {
        // reading
      }
    }
    return k;
  }

  static BreakReport reconcile(final SyntheticDay.Result day, final long cut) {
    final PositionKeeper atCut = keeper(day.journal(), cut);
    final PositionKeeper full = keeper(day.journal(), Long.MAX_VALUE);
    final ClearingHouseSimulator ccp = new ClearingHouseSimulator((k, why) -> { }).limit(20, Long.MAX_VALUE);
    for (final Trade t : atCut.trades()) {
      if (t.isLive() && t.instrumentId() == SyntheticDay.SWAP) {
        ccp.submit(t, SyntheticDay.CUT);
      }
    }
    ccp.endOfCycle(SyntheticDay.CUT);
    return DailyReconciliation.run(new DailyReconciliation.Inputs(day.date(), atCut, full, cut, day.dropCopy(),
        atCut.orderRecords(), ccp.clearedReport(), i -> i == SyntheticDay.SWAP));
  }

  public static void main(final String[] args) throws IOException {
    final Path dir = Files.createTempDirectory("recipe20");
    final Path journal = dir.resolve("day.raj");
    final SyntheticDay.Result day = SyntheticDay.generate(journal, 20261030L, SyntheticDay.Params.DEFAULT);
    System.out.println("day " + day.date() + ": frames=" + day.frames() + " fills=" + day.fills()
        + " cut-sequence=" + day.cutSequence());
    System.out.println("injected: " + day.injected());

    System.out.print(reconcile(day, day.cutSequence()).render());
    final BreakReport endOfDay = reconcile(day, Long.MAX_VALUE);
    System.out.println("after every late message: breaks=" + endOfDay.breaks().size()
        + " unexplained=" + endOfDay.unexplained());
    Files.delete(journal);
    Files.delete(dir);
  }
}
