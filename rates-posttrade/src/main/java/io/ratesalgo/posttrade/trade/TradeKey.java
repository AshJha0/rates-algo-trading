package io.ratesalgo.posttrade.trade;

import io.ratesalgo.messages.sbe.TradeSource;

/**
 * Identity of a trade across every message about it (API_SPEC.md Section 17.1).
 *
 * <p>An {@link TradeSource#Execution} trade is named by the venue and its {@code execId}; an
 * {@link TradeSource#Rfq} trade by the venue and our {@code quoteId}. Duplicates, busts, amends,
 * drop copies and clearing reports all carry this key.
 *
 * @param venueId venue that executed the trade
 * @param source which message created the trade
 * @param tradeId execId or quoteId
 */
public record TradeKey(int venueId, TradeSource source, long tradeId) implements Comparable<TradeKey> {

  public TradeKey {
    if (source == null || source == TradeSource.NULL_VAL) {
      throw new IllegalArgumentException("trade source required for trade " + tradeId);
    }
  }

  /** Stable text form used in records, reports and reconciliation match keys. */
  public String text() {
    return venueId + (source == TradeSource.Execution ? ":E:" : ":R:") + tradeId;
  }

  /** Parses {@link #text()}. */
  public static TradeKey parse(final String text) {
    final int first = text.indexOf(':');
    final int second = text.indexOf(':', first + 1);
    if (first < 0 || second != first + 2) {
      throw new IllegalArgumentException("not a trade key: " + text);
    }
    final TradeSource source =
        switch (text.charAt(first + 1)) {
          case 'E' -> TradeSource.Execution;
          case 'R' -> TradeSource.Rfq;
          default -> throw new IllegalArgumentException("not a trade key: " + text);
        };
    return new TradeKey(
        Integer.parseInt(text.substring(0, first)), source, Long.parseLong(text.substring(second + 1)));
  }

  @Override
  public int compareTo(final TradeKey other) {
    int c = Integer.compare(venueId, other.venueId);
    if (c == 0) {
      c = Integer.compare(source.value(), other.source.value());
    }
    return c != 0 ? c : Long.compare(tradeId, other.tradeId);
  }

  @Override
  public String toString() {
    return text();
  }
}
