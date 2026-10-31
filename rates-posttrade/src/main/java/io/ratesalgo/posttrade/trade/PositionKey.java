package io.ratesalgo.posttrade.trade;

/** A position is kept per book, account and instrument. */
public record PositionKey(int book, int account, int instrumentId) implements Comparable<PositionKey> {

  @Override
  public int compareTo(final PositionKey o) {
    int c = Integer.compare(book, o.book);
    if (c == 0) {
      c = Integer.compare(account, o.account);
    }
    return c != 0 ? c : Integer.compare(instrumentId, o.instrumentId);
  }

  /** Text form {@code book/account/instrument} used in reports. */
  public String text() {
    return book + "/" + account + "/" + instrumentId;
  }
}
