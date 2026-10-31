package io.ratesalgo.posttrade.recovery;

import io.ratesalgo.journal.FrameHandler;
import io.ratesalgo.journal.JournalReader;
import io.ratesalgo.posttrade.position.LiveQuote;
import io.ratesalgo.posttrade.position.OpenOrder;
import io.ratesalgo.posttrade.position.PositionKeeper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Restart after a crash or a failover (docs/runbooks/standby-takeover.md, API_SPEC.md Section
 * 17.9). The order of steps is the contract:
 *
 * <ol>
 *   <li>Disable quoting before anything else.
 *   <li>Replay the journal to its last sequence into the keeper (and any other handler the
 *       process registers), verifying the hash chain.
 *   <li>For each venue, ask for the status of our orders and quotes and compare with the journal:
 *       <ul>
 *         <li>an order the venue holds that the journal does not know, or knows as finished: cancel
 *             it, and record the action;
 *         <li>an order the journal holds as open that the venue does not hold: the venue finished it
 *             while we were down; record it, the fills arrive by drop copy;
 *         <li>an order where the venue's cumulative quantity is larger than ours: fills are
 *             missing from the journal; this is a discrepancy that blocks quoting;
 *         <li>every live quote at the venue is withdrawn, whatever the journal says.
 *       </ul>
 *   <li>Only if no blocking discrepancy remains, enable quoting.
 * </ol>
 */
public final class RestartProcedure {

  /** What the procedure did, in order, and whether quoting was enabled. */
  public record Report(long replayedFrames, long lastSequence, List<String> actions,
      List<String> blockingDiscrepancies, boolean quotingEnabled) {

    public Report {
      actions = List.copyOf(actions);
      blockingDiscrepancies = List.copyOf(blockingDiscrepancies);
    }
  }

  private final PositionKeeper keeper;
  private final VenueStatusPort venues;
  private final QuotingControl quoting;

  public RestartProcedure(
      final PositionKeeper keeper, final VenueStatusPort venues, final QuotingControl quoting) {
    this.keeper = keeper;
    this.venues = venues;
    this.quoting = quoting;
  }

  /**
   * Runs the procedure.
   *
   * @param journal the trading process's journal
   * @param venueIds venues to query
   * @param nowNanos wall-clock time, for deciding which journal quotes could still be live
   * @param also further handlers to drive from the same replay (risk, quoting state)
   */
  public Report run(final Path journal, final int[] venueIds, final long nowNanos, final FrameHandler... also) {
    final List<String> actions = new ArrayList<>();
    final List<String> blocking = new ArrayList<>();
    quoting.disable("restart");
    actions.add("quoting disabled");

    long frames = 0;
    try (JournalReader reader = JournalReader.open(journal)) {
      final FrameHandler fanout =
          (seq, ts, id, v, buf, off, len) -> {
            keeper.onFrame(seq, ts, id, v, buf, off, len);
            for (final FrameHandler h : also) {
              h.onFrame(seq, ts, id, v, buf, off, len);
            }
          };
      while (reader.next(fanout)) {
        frames++;
      }
    }
    actions.add("replayed " + frames + " frames to sequence " + keeper.lastSequence());

    final Map<Long, OpenOrder> journalOpen = new HashMap<>();
    for (final OpenOrder o : keeper.openOrders()) {
      journalOpen.put(o.clOrdId(), o);
    }
    for (final int venueId : venueIds) {
      final Map<Long, VenueStatusPort.OrderStatus> atVenue = new HashMap<>();
      for (final VenueStatusPort.OrderStatus s : venues.orderMassStatus(venueId)) {
        atVenue.put(s.clOrdId(), s);
        final OpenOrder ours = journalOpen.get(s.clOrdId());
        if (ours == null) {
          venues.cancelOrder(venueId, s.clOrdId(), s.venueOrderId());
          actions.add("venue " + venueId + ": cancelled order " + s.clOrdId() + " unknown or finished in journal");
        } else if (s.cumQuantity() > ours.cumQuantity()) {
          blocking.add("venue " + venueId + ": order " + s.clOrdId() + " venue cum " + s.cumQuantity()
              + " journal cum " + ours.cumQuantity() + ", fills missing");
        }
      }
      for (final OpenOrder o : journalOpen.values()) {
        if (o.venueId() == venueId && !atVenue.containsKey(o.clOrdId())) {
          actions.add("venue " + venueId + ": order " + o.clOrdId() + " finished while down, await drop copy");
        }
      }
      for (final VenueStatusPort.QuoteStatus q : venues.quoteStatus(venueId)) {
        venues.cancelQuote(venueId, q.quoteId());
        actions.add("venue " + venueId + ": withdrew quote " + q.quoteId());
      }
      for (final LiveQuote q : keeper.liveQuotes(nowNanos)) {
        if (q.venueId() == venueId) {
          actions.add("venue " + venueId + ": journal quote " + q.quoteId() + " treated as withdrawn");
        }
      }
    }

    if (blocking.isEmpty()) {
      quoting.enable("restart reconciled");
      actions.add("quoting enabled");
    } else {
      actions.add("quoting left disabled: " + blocking.size() + " blocking discrepancies");
    }
    return new Report(frames, keeper.lastSequence(), actions, blocking, quoting.enabled());
  }
}
