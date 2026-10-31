package io.ratesalgo.backtest;

/**
 * One RFQ as the back-tester saw and settled it. Mutable while the RFQ is live; read-only once the
 * run has finished.
 */
public final class RfqRecord {

  final long rfqId;
  final long receivedNanos;
  final long expiryNanos;
  final int clientId;
  final int tier;
  final int instrumentId;
  final boolean clientBuys;
  final long quantity;
  final int dealers;
  final boolean inFixingWindow;

  boolean quoted;
  long quoteSentNanos;
  long ourPriceMantissa;
  double midAtQuote;
  double ourSpreadBp;
  boolean settled;
  boolean won;
  double coverBp;
  double winProbability;
  long tradedPriceMantissa;

  RfqRecord(
      final long rfqId,
      final long receivedNanos,
      final long expiryNanos,
      final int clientId,
      final int tier,
      final int instrumentId,
      final boolean clientBuys,
      final long quantity,
      final int dealers,
      final boolean inFixingWindow) {
    this.rfqId = rfqId;
    this.receivedNanos = receivedNanos;
    this.expiryNanos = expiryNanos;
    this.clientId = clientId;
    this.tier = tier;
    this.instrumentId = instrumentId;
    this.clientBuys = clientBuys;
    this.quantity = quantity;
    this.dealers = dealers;
    this.inFixingWindow = inFixingWindow;
  }

  public long rfqId() {
    return rfqId;
  }

  public long receivedNanos() {
    return receivedNanos;
  }

  public int clientId() {
    return clientId;
  }

  public int tier() {
    return tier;
  }

  public int instrumentId() {
    return instrumentId;
  }

  public boolean clientBuys() {
    return clientBuys;
  }

  public long quantity() {
    return quantity;
  }

  public double sizeMillions() {
    return (double) quantity / InstrumentSet.MILLION;
  }

  public int dealers() {
    return dealers;
  }

  public boolean inFixingWindow() {
    return inFixingWindow;
  }

  public boolean quoted() {
    return quoted;
  }

  public long responseNanos() {
    return quoted ? quoteSentNanos - receivedNanos : 0L;
  }

  public long ourPriceMantissa() {
    return ourPriceMantissa;
  }

  public double midAtQuote() {
    return midAtQuote;
  }

  public double ourSpreadBp() {
    return ourSpreadBp;
  }

  public boolean settled() {
    return settled;
  }

  public boolean won() {
    return won;
  }

  public double coverBp() {
    return coverBp;
  }

  public double winProbability() {
    return winProbability;
  }

  public long tradedPriceMantissa() {
    return tradedPriceMantissa;
  }

  /**
   * Spread-to-cover in bp: positive when our quote was behind the best competitor. For a won RFQ,
   * minus this is the money left on the table per bp; for a lost one it is how far we had to
   * improve to have been best.
   */
  public double spreadToCoverBp() {
    return ourSpreadBp - coverBp;
  }
}
