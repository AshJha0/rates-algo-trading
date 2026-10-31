package io.ratesalgo.posttrade.recon;

import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.records.OrderRecord;
import io.ratesalgo.posttrade.trade.Trade;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/**
 * The daily reconciliation run (API_SPEC.md Section 17.8; docs/runbooks/reconciliation-break.md).
 * Five reconciliations, in this order:
 *
 * <ol>
 *   <li>{@code drop-copy}: our trades at the cut against the venues' drop copies, trade by trade;
 *   <li>{@code drop-copy-pos}: net quantity per instrument against the drop copies, our side
 *       adjusted for every trade-level break the journal explains (a late fill is added, a bust
 *       after the cut is removed), so that an explained trade break does not reappear as an
 *       unexplained position break;
 *   <li>{@code books-records}: our trades against the trades rebuilt from the archived order
 *       records (the books and records must agree with the position keeper);
 *   <li>{@code clearing}: net quantity per account and cleared instrument against the clearing
 *       house's report of cleared trades;
 *   <li>{@code pending-busts}: busts still waiting for their fill, each a MISSING_OURS break.
 * </ol>
 */
public final class DailyReconciliation {

  /**
   * What the run needs.
   *
   * @param date business date
   * @param atCut a keeper that read the journal up to {@code cutSequence}
   * @param full a keeper that read the whole journal
   * @param cutSequence last sequence in the snapshot
   * @param dropCopy the venues' drop copies
   * @param archivedRecords the archived order records for the date
   * @param clearedByCcp the clearing house's cleared-trade report
   * @param clearedInstrument instruments that clear (swaps)
   */
  public record Inputs(LocalDate date, PositionKeeper atCut, PositionKeeper full, long cutSequence,
      List<ReconItem> dropCopy, List<OrderRecord> archivedRecords, List<Trade> clearedByCcp,
      IntPredicate clearedInstrument) {
    public Inputs {
      dropCopy = List.copyOf(dropCopy);
      archivedRecords = List.copyOf(archivedRecords);
      clearedByCcp = List.copyOf(clearedByCcp);
    }
  }

  private DailyReconciliation() {}

  /** Runs every reconciliation and returns the break report. */
  public static BreakReport run(final Inputs in) {
    final KeeperFacts facts = new KeeperFacts(in.full(), in.cutSequence());
    final List<ReconItem> ours = ReconItem.fromTrades(in.atCut().trades());
    final List<ReconResult> results = new ArrayList<>();

    final ReconResult dropCopy =
        Reconciler.trades("drop-copy", ours, in.dropCopy(), Reconciler.Tolerance.DEFAULT, facts);
    results.add(dropCopy);

    final Map<String, Long> oursByInstrument = netByInstrument(ours);
    final Map<String, ReconItem> fullTrades = new TreeMap<>();
    for (final ReconItem i : ReconItem.fromTrades(in.full().trades())) {
      fullTrades.put(i.matchKey(), i);
    }
    for (final Break b : dropCopy.breaks()) {
      if (b.explanation() == Explanation.LATE_ARRIVAL) {
        final ReconItem late = fullTrades.get(b.matchKey());
        oursByInstrument.merge(instrumentKey(late.instrumentId()), signed(late), Long::sum);
      } else if (b.explanation() == Explanation.BUSTED_AFTER_CUT) {
        for (final ReconItem i : ours) {
          if (i.matchKey().equals(b.matchKey())) {
            oursByInstrument.merge(instrumentKey(i.instrumentId()), -signed(i), Long::sum);
          }
        }
      }
    }
    results.add(Reconciler.positions(
        "drop-copy-pos", oursByInstrument, netByInstrument(dedupe(in.dropCopy())), BreakExplainer.NONE));

    results.add(Reconciler.trades("books-records", ours, ReconItem.fromRecords(in.archivedRecords()),
        Reconciler.Tolerance.DEFAULT, facts));

    final Map<String, Long> oursCleared = new TreeMap<>();
    for (final Trade t : in.atCut().trades()) {
      if (t.isLive() && in.clearedInstrument().test(t.instrumentId())) {
        oursCleared.merge(t.booking().account() + "/" + t.instrumentId(), t.signedQuantity(), Long::sum);
      }
    }
    final Map<String, Long> ccp = new TreeMap<>();
    for (final Trade t : in.clearedByCcp()) {
      ccp.merge(t.booking().account() + "/" + t.instrumentId(), t.signedQuantity(), Long::sum);
    }
    results.add(Reconciler.positions("clearing", oursCleared, ccp, facts));

    final List<Break> pending = new ArrayList<>();
    in.atCut().pendingBusts().stream().sorted().forEach(k -> pending.add(new Break(
        "pending-busts", k.text(), BreakClass.MISSING_OURS, facts.explain(k.text(), BreakClass.MISSING_OURS),
        "-", "bust without fill")));
    results.add(new ReconResult("pending-busts", 0, pending.size(), 0, 0, pending));

    return new BreakReport(in.date(), results);
  }

  private static List<ReconItem> dedupe(final List<ReconItem> items) {
    final Map<String, ReconItem> out = new TreeMap<>();
    for (final ReconItem i : items) {
      out.putIfAbsent(i.matchKey(), i);
    }
    return new ArrayList<>(out.values());
  }

  private static Map<String, Long> netByInstrument(final List<ReconItem> items) {
    final Map<String, Long> out = new TreeMap<>();
    for (final ReconItem i : items) {
      out.merge(instrumentKey(i.instrumentId()), signed(i), Long::sum);
    }
    return out;
  }

  private static String instrumentKey(final int instrumentId) {
    return "instrument " + instrumentId;
  }

  private static long signed(final ReconItem i) {
    return i.side() == Side.Buy ? i.quantity() : -i.quantity();
  }
}
