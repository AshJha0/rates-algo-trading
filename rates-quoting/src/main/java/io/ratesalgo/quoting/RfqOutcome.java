package io.ratesalgo.quoting;

import io.ratesalgo.messages.sbe.Side;

/** The engine's last RFQ decision and the numbers behind it; reused, never allocated per RFQ. */
public final class RfqOutcome {

  int venueId;
  long rfqId;
  int clientId;
  int instrumentId;
  RfqDecision decision = RfqDecision.PASS;
  PassReason reason = PassReason.NONE;
  long quoteId;
  Side ourSide = Side.NULL_VAL;
  long price;
  long quantity;
  long requestedQuantity;
  double fair;
  double halfSpreadBp;
  double skewBp;
  double sizeTermBp;
  double axeBp;
  double distanceBp;
  double costBp;
  double winProbability;
  long validUntilNanos;
  int limitRejection;
  double breakEvenBp = Double.NaN;
  double predictedCoverBp = Double.NaN;
  double distanceTicks = Double.NaN;

  void reset() {
    venueId = 0;
    rfqId = 0;
    clientId = 0;
    instrumentId = 0;
    decision = RfqDecision.PASS;
    reason = PassReason.NONE;
    quoteId = 0;
    ourSide = Side.NULL_VAL;
    price = 0;
    quantity = 0;
    requestedQuantity = 0;
    fair = 0.0;
    halfSpreadBp = 0.0;
    skewBp = 0.0;
    sizeTermBp = 0.0;
    axeBp = 0.0;
    distanceBp = 0.0;
    costBp = 0.0;
    winProbability = 0.0;
    validUntilNanos = 0;
    limitRejection = 0;
    breakEvenBp = Double.NaN;
    predictedCoverBp = Double.NaN;
    distanceTicks = Double.NaN;
  }

  public int venueId() {
    return venueId;
  }

  public long rfqId() {
    return rfqId;
  }

  public int clientId() {
    return clientId;
  }

  public int instrumentId() {
    return instrumentId;
  }

  public RfqDecision decision() {
    return decision;
  }

  public PassReason reason() {
    return reason;
  }

  public long quoteId() {
    return quoteId;
  }

  /** Our side of the quote: Sell when the client buys. */
  public Side ourSide() {
    return ourSide;
  }

  /** Quoted price, Decimal9. */
  public long price() {
    return price;
  }

  /** Quoted quantity after any size reduction. */
  public long quantity() {
    return quantity;
  }

  public long requestedQuantity() {
    return requestedQuantity;
  }

  /** Fair value used, quote units. */
  public double fair() {
    return fair;
  }

  /** Half-spread after widening and tiering, bp of value. */
  public double halfSpreadBp() {
    return halfSpreadBp;
  }

  /** Inventory skew, bp; positive lowers both sides. */
  public double skewBp() {
    return skewBp;
  }

  /** Size penalty, bp. */
  public double sizeTermBp() {
    return sizeTermBp;
  }

  /** Axe improvement, bp. */
  public double axeBp() {
    return axeBp;
  }

  /** Final distance of the quote from fair on our side, bp (negative is through fair). */
  public double distanceBp() {
    return distanceBp;
  }

  /** Adverse selection plus winner's curse plus hedge cost, bp. */
  public double costBp() {
    return costBp;
  }

  /** Model win probability at the quoted distance. */
  public double winProbability() {
    return winProbability;
  }

  public long validUntilNanos() {
    return validUntilNanos;
  }

  /**
   * Strategy path (Round 5): the break-even distance the quote may not go below, bp (adverse
   * cost, hedge cost and inventory term); NaN on the default path.
   */
  public double breakEvenBp() {
    return breakEvenBp;
  }

  /** Strategy path with cover pricing: median of the predicted best competing distance, bp; else NaN. */
  public double predictedCoverBp() {
    return predictedCoverBp;
  }

  /** Strategy path with tick rounding: the quoted distance from fair in venue ticks; else NaN. */
  public double distanceTicks() {
    return distanceTicks;
  }

  /** The risk engine's code when the reason is RISK_REJECT. */
  public int limitRejection() {
    return limitRejection;
  }
}
