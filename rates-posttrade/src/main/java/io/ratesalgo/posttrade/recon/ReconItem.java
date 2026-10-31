package io.ratesalgo.posttrade.recon;

import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.posttrade.records.OrderRecord;
import io.ratesalgo.posttrade.trade.Trade;
import io.ratesalgo.posttrade.trade.TradeKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One trade as one side of a reconciliation sees it (API_SPEC.md Section 17.8).
 *
 * @param matchKey the trade key text ({@code venue:E:execId} or {@code venue:R:quoteId})
 * @param instrumentId instrument
 * @param side our side
 * @param quantity minor units
 * @param price Decimal9 mantissa
 * @param transactNanos transaction time
 */
public record ReconItem(
    String matchKey, int instrumentId, Side side, long quantity, long price, long transactNanos) {

  /** The live trades of a keeper as reconciliation items. */
  public static List<ReconItem> fromTrades(final List<Trade> trades) {
    final List<ReconItem> out = new ArrayList<>();
    for (final Trade t : trades) {
      if (t.isLive()) {
        out.add(of(t));
      }
    }
    return out;
  }

  /** One trade as a reconciliation item. */
  public static ReconItem of(final Trade t) {
    return new ReconItem(t.key().text(), t.instrumentId(), t.side(), t.quantity(), t.price(),
        t.transactNanos());
  }

  /**
   * Rebuilds executed trades from archived order records: a FILL or PARF event opens a trade,
   * AMND replaces its price and quantity, BUST removes it.
   */
  public static List<ReconItem> fromRecords(final List<OrderRecord> records) {
    final Map<String, ReconItem> live = new TreeMap<>();
    for (final OrderRecord r : records) {
      if (r.tradeKey().isEmpty()) {
        continue;
      }
      final Side side = "BUYI".equals(r.buySell()) ? Side.Buy : Side.Sell;
      switch (r.eventType()) {
        case "FILL", "PARF" ->
            live.put(r.tradeKey(), new ReconItem(r.tradeKey(), r.instrumentId(), side,
                r.tradedQuantity(), r.limitPrice(), r.receiptNanos()));
        case "AMND" -> {
          final ReconItem old = live.get(r.tradeKey());
          if (old != null) {
            live.put(r.tradeKey(), new ReconItem(old.matchKey(), old.instrumentId(), old.side(),
                r.tradedQuantity(), r.limitPrice(), old.transactNanos()));
          }
        }
        case "BUST" -> live.remove(r.tradeKey());
        default -> {
          // order events without an execution
        }
      }
    }
    return new ArrayList<>(live.values());
  }

  /** The trade key this item names. */
  public TradeKey tradeKey() {
    return TradeKey.parse(matchKey);
  }

  /** Text used in break reports. */
  public String describe() {
    return side + " " + quantity + "@" + price + " t=" + transactNanos;
  }
}
