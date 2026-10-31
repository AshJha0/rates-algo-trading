package io.ratesalgo.app.posttrade;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.recon.BreakExplainer;
import io.ratesalgo.posttrade.recon.ReconItem;
import io.ratesalgo.posttrade.recon.ReconResult;
import io.ratesalgo.posttrade.recon.Reconciler;
import io.ratesalgo.posttrade.trade.TradeKey;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.d2c.TradeRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * The daily trade reconciliation of a simulated day (API_SPEC.md Section 17.8): the keeper's live
 * trades (ours) against the venues' own trade registers (theirs), the D2C simulator's RFQ trades
 * won by our CompID and the CLOB simulator's fills to our CompID that were not busted. Venue
 * quantities are currency units and become minor units (times 100) here.
 */
public final class VenueReconciliation {

  private static final long MINOR_PER_UNIT = 100L;

  private VenueReconciliation() {}

  /** The venues' side. */
  public static List<ReconItem> theirs(final D2cRfqSimulator d2c, final ClobSimulator clob, final String compId) {
    final List<ReconItem> items = new ArrayList<>();
    for (final TradeRecord t : d2c.trades()) {
      if (compId.equals(t.winner())) {
        items.add(new ReconItem(new TradeKey(AppIds.D2C_VENUE, TradeSource.Rfq, t.quoteId()).text(), t.instrumentId(),
            t.clientBuys() ? Side.Sell : Side.Buy, t.quantity() * MINOR_PER_UNIT, t.price(), t.tradeNanos()));
      }
    }
    for (final ClobSimulator.Execution e : clob.executions(compId)) {
      items.add(new ReconItem(new TradeKey(AppIds.CLOB_VENUE, TradeSource.Execution, e.execId()).text(), e.instrumentId(),
          e.buy() ? Side.Buy : Side.Sell, e.quantity() * MINOR_PER_UNIT, e.price(), e.epochNanos()));
    }
    return items;
  }

  /** Reconciles the keeper against the venues with exact prices and one second of timing. */
  public static ReconResult run(final PositionKeeper keeper, final D2cRfqSimulator d2c, final ClobSimulator clob) {
    return Reconciler.trades(
        "trades", ReconItem.fromTrades(keeper.trades()), theirs(d2c, clob, AppIds.COMP_ID),
        Reconciler.Tolerance.DEFAULT, BreakExplainer.NONE);
  }
}
