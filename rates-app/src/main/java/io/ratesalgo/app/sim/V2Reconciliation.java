package io.ratesalgo.app.sim;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.posttrade.JournalFeed;
import io.ratesalgo.app.pricing.PricingBinding;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.position.InstrumentCatalogue;
import io.ratesalgo.posttrade.position.InstrumentReference;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.recon.BreakExplainer;
import io.ratesalgo.posttrade.recon.ReconItem;
import io.ratesalgo.posttrade.recon.ReconResult;
import io.ratesalgo.posttrade.recon.Reconciler;
import io.ratesalgo.posttrade.trade.BookAssignment;
import io.ratesalgo.posttrade.trade.FixedBookMapper;
import io.ratesalgo.posttrade.trade.TradeKey;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.d2c.TradeRecord;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The end-of-day trade reconciliation of the v2 desk profile: the position keeper rebuilt from
 * the loop's journals against the trade registers of all four simulator v2 venues, the D2C
 * venue's RFQ trades won by our CompID and every order book's fills to it.
 *
 * <p>Both sides are in the venues' units. The journals hold what crossed the wire, and a futures
 * venue counts contracts, so a futures position here is contracts (times 100, as every venue
 * quantity becomes minor units); the catalogue carries the contract size so that it is valued as
 * face. The loop's own risk position is in face (API_SPEC.md Section 0).
 */
public final class V2Reconciliation {

  private static final long MINOR_PER_UNIT = 100L;

  private V2Reconciliation() {}

  /** RFQ trades to book 1; hedges in Treasuries and futures to book 2; account 1. */
  public static FixedBookMapper books(final UsdUniverse universe) {
    final FixedBookMapper m = new FixedBookMapper(new BookAssignment(1, 1, "RALGO-RFQ"));
    for (final UsdUniverse.BondSpec b : universe.bonds()) {
      m.with(b.instrumentId(), new BookAssignment(2, 1, "RALGO-HEDGE"));
    }
    for (final UsdUniverse.FutureSpec f : universe.futures()) {
      m.with(f.instrumentId(), new BookAssignment(2, 1, "RALGO-HEDGE"));
    }
    return m;
  }

  /** The post-trade catalogue: swaps per annuity, bonds per 100, futures per 100 of a contract's face. */
  public static InstrumentCatalogue catalogue(final PricingBinding pricing) {
    final InstrumentCatalogue c = new InstrumentCatalogue();
    final UsdUniverse universe = pricing.universe();
    for (final UsdUniverse.SwapSpec s : universe.swaps()) {
      final double annuity = pricing.directory().byId(s.instrumentId()).quoteValue() * 100.0;
      c.add(new InstrumentReference(s.instrumentId(), s.securityId(), "USD", "PERC", BigDecimal.valueOf(annuity)));
    }
    for (final UsdUniverse.BondSpec b : universe.bonds()) {
      c.add(new InstrumentReference(b.instrumentId(), b.securityId(), "USD", "PERC", BigDecimal.ONE));
    }
    for (final UsdUniverse.FutureSpec f : universe.futures()) {
      c.add(
          new InstrumentReference(
              f.instrumentId(), f.securityId(), "USD", "PERC", BigDecimal.valueOf(f.contract().contractSize())));
    }
    return c;
  }

  /** The position keeper rebuilt from a group's journals. */
  public static PositionKeeper keeper(final Path groupDir, final PricingBinding pricing) {
    return JournalFeed.keeper(groupDir, "in", "out", books(pricing.universe()), catalogue(pricing));
  }

  /** The venues' side: every trade any of them did with {@code compId}. */
  public static List<ReconItem> theirs(final SimulatedVenuesV2 venues, final String compId) {
    final List<ReconItem> items = new ArrayList<>();
    for (final TradeRecord t : venues.d2c().trades()) {
      if (compId.equals(t.winner())) {
        items.add(new ReconItem(new TradeKey(AppIds.D2C_VENUE, TradeSource.Rfq, t.quoteId()).text(), t.instrumentId(),
            t.clientBuys() ? Side.Sell : Side.Buy, t.quantity() * MINOR_PER_UNIT, t.price(), t.tradeNanos()));
      }
    }
    for (final ClobSimulator book : venues.books()) {
      for (final ClobSimulator.Execution e : book.executions(compId)) {
        items.add(new ReconItem(new TradeKey(book.venueId(), TradeSource.Execution, e.execId()).text(), e.instrumentId(),
            e.buy() ? Side.Buy : Side.Sell, e.quantity() * MINOR_PER_UNIT, e.price(), e.epochNanos()));
      }
    }
    return items;
  }

  /** Fills an order-book venue's register holds for {@code compId}. */
  public static int fills(final ClobSimulator book, final String compId) {
    return book.executions(compId).size();
  }

  /** Reconciles the keeper against every venue with exact prices and one second of timing. */
  public static ReconResult run(final PositionKeeper keeper, final SimulatedVenuesV2 venues) {
    return Reconciler.trades(
        "trades", ReconItem.fromTrades(keeper.trades()), theirs(venues, AppIds.COMP_ID), Reconciler.Tolerance.DEFAULT,
        BreakExplainer.NONE);
  }
}
