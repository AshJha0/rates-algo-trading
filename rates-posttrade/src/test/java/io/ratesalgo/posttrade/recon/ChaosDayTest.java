package io.ratesalgo.posttrade.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.journal.FrameHandler;
import io.ratesalgo.journal.JournalReader;
import io.ratesalgo.posttrade.Fixtures;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import io.ratesalgo.posttrade.clearing.ClearingHouseSimulator;
import io.ratesalgo.posttrade.position.AuditKind;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.records.RecordArchive;
import io.ratesalgo.posttrade.trade.Trade;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance: zero unexplained breaks on the synthetic day with injected duplicate, late and
 * busted fills (API_SPEC.md Section 17.8, COOKBOOK.md recipe 20).
 */
class ChaosDayTest {

  @TempDir Path dir;

  /** Reads frames up to and including {@code last}. */
  static PositionKeeper keeperTo(final Path journal, final long last) {
    final PositionKeeper k = new PositionKeeper(Fixtures.books(), Fixtures.catalogue());
    try (JournalReader r = JournalReader.open(journal)) {
      final FrameHandler h = (s, t, id, v, b, o, l) -> {
        if (s <= last) {
          k.onFrame(s, t, id, v, b, o, l);
        }
      };
      while (r.next(h)) {
        // reading
      }
    }
    return k;
  }

  static DailyReconciliation.Inputs inputs(final SyntheticDay.Result day, final Path archiveRoot,
      final List<ReconItem> dropCopy, final long cut) {
    final PositionKeeper atCut = keeperTo(day.journal(), cut);
    final PositionKeeper full = keeperTo(day.journal(), Long.MAX_VALUE);
    final RecordArchive archive = new RecordArchive(archiveRoot, 5);
    archive.append(day.date(), atCut.orderRecords());
    final ClearingHouseSimulator ccp = new ClearingHouseSimulator((k, why) -> { }).limit(20, Long.MAX_VALUE);
    for (final Trade t : atCut.trades()) {
      if (t.isLive() && t.instrumentId() == SyntheticDay.SWAP) {
        ccp.submit(t, SyntheticDay.CUT);
      }
    }
    ccp.endOfCycle(SyntheticDay.CUT);
    return new DailyReconciliation.Inputs(day.date(), atCut, full, cut, dropCopy,
        archive.read(day.date()), ccp.clearedReport(), i -> i == SyntheticDay.SWAP);
  }

  @Test
  void syntheticDayHasZeroUnexplainedBreaks() {
    final SyntheticDay.Result day =
        SyntheticDay.generate(dir.resolve("day.raj"), 20261030L, SyntheticDay.Params.DEFAULT);
    final BreakReport atCut =
        DailyReconciliation.run(inputs(day, dir.resolve("a"), day.dropCopy(), day.cutSequence()));
    assertEquals(0, atCut.unexplained(), atCut::render);
    // the faults surfaced as explained breaks, not as silence
    final List<Explanation> explanations = new ArrayList<>();
    for (final Break b : atCut.breaks()) {
      explanations.add(b.explanation());
    }
    assertEquals(3, explanations.stream().filter(e -> e == Explanation.LATE_ARRIVAL).count(), atCut::render);
    assertEquals(1, explanations.stream().filter(e -> e == Explanation.BUSTED_AFTER_CUT).count(), atCut::render);
    final ReconResult dropCopy = atCut.results().get(0);
    assertEquals(2, dropCopy.duplicatesSuppressed());
    for (final ReconResult r : atCut.results().subList(1, atCut.results().size())) {
      assertEquals(0, r.breaks().size(), atCut::render);
    }

    final PositionKeeper full = keeperTo(day.journal(), Long.MAX_VALUE);
    assertEquals(5, full.audit(AuditKind.DUPLICATE_IGNORED).size());
    assertEquals(4, full.audit(AuditKind.BUSTED).size());
    assertEquals(1, full.audit(AuditKind.BUST_PENDING).size());
    assertTrue(full.audit(AuditKind.LATE_FILL).size() >= 2);

    // after every late message has arrived, nothing breaks at all
    final BreakReport endOfDay =
        DailyReconciliation.run(inputs(day, dir.resolve("b"), day.dropCopy(), Long.MAX_VALUE));
    assertEquals(0, endOfDay.breaks().size(), endOfDay::render);
  }

  @Test
  void aGenuinePriceBreakIsUnexplained() {
    final SyntheticDay.Result day =
        SyntheticDay.generate(dir.resolve("day.raj"), 7L, SyntheticDay.Params.DEFAULT);
    final List<ReconItem> tampered = new ArrayList<>(day.dropCopy());
    final ReconItem first = tampered.get(0);
    final ReconItem wrong = new ReconItem(first.matchKey(), first.instrumentId(), first.side(), first.quantity(),
        first.price() + 31_250_000L, first.transactNanos());
    tampered.replaceAll(i -> i.equals(first) ? wrong : i);
    final BreakReport report =
        DailyReconciliation.run(inputs(day, dir.resolve("c"), tampered, Long.MAX_VALUE));
    assertEquals(1, report.unexplained(), report::render);
    final Break b = report.breaks().stream().filter(x -> !x.explained()).findFirst().orElseThrow();
    assertEquals(BreakClass.PRICE, b.breakClass());
    assertEquals(first.matchKey(), b.matchKey());
    assertTrue(report.render().contains("unexplained breaks: 1"));
  }

  @Test
  void generationIsDeterministic() {
    final SyntheticDay.Result a = SyntheticDay.generate(dir.resolve("a.raj"), 99L, SyntheticDay.Params.DEFAULT);
    final SyntheticDay.Result b = SyntheticDay.generate(dir.resolve("b.raj"), 99L, SyntheticDay.Params.DEFAULT);
    assertEquals(a.dropCopy(), b.dropCopy());
    assertEquals(a.frames(), b.frames());
    assertEquals(io.ratesalgo.journal.Replayer.finalHash(a.journal()),
        io.ratesalgo.journal.Replayer.finalHash(b.journal()));
  }
}
