package io.ratesalgo.venues.clob;

/** A resting or held order. Mutable, owned by the book's thread. */
public final class ClobOrder {

  final long venueOrderId;
  final String owner;
  long clOrdId;
  final boolean buy;
  long price;
  long quantity;
  long remaining;
  final long display;
  long visible;
  long cumQty;
  long priority;
  final boolean immediateOrCancel;
  boolean done;

  ClobOrder(
      final long venueOrderId,
      final String owner,
      final long clOrdId,
      final boolean buy,
      final long price,
      final long quantity,
      final long display,
      final boolean immediateOrCancel) {
    this.venueOrderId = venueOrderId;
    this.owner = owner;
    this.clOrdId = clOrdId;
    this.buy = buy;
    this.price = price;
    this.quantity = quantity;
    this.remaining = quantity;
    this.display = display;
    this.immediateOrCancel = immediateOrCancel;
  }

  /** Venue order id. */
  public long venueOrderId() {
    return venueOrderId;
  }

  /** Owner (CompID or internal participant code). */
  public String owner() {
    return owner;
  }

  /** Client order id. */
  public long clOrdId() {
    return clOrdId;
  }

  /** Side. */
  public boolean isBuy() {
    return buy;
  }

  /** Limit price mantissa. */
  public long price() {
    return price;
  }

  /** Total quantity. */
  public long quantity() {
    return quantity;
  }

  /** Quantity not yet filled or cancelled. */
  public long remaining() {
    return remaining;
  }

  /** Quantity shown on the book now. */
  public long visible() {
    return visible;
  }

  /** Filled quantity. */
  public long cumQty() {
    return cumQty;
  }

  /** Iceberg peak (0 for a fully displayed order). */
  public long display() {
    return display;
  }

  /** True once filled or cancelled. */
  public boolean isDone() {
    return done;
  }
}
