package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.OrdStatus;
import java.util.Objects;

/**
 * One line of a venue's mass order status after a reconnect: what the venue believes about one of
 * our orders.
 *
 * @param clOrdId our child order id
 * @param status the venue's order status
 * @param cumQuantity the venue's cumulative filled quantity
 */
public record VenueOrderStatus(long clOrdId, OrdStatus status, long cumQuantity) {

  /** Validates the line. */
  public VenueOrderStatus {
    Objects.requireNonNull(status, "status");
    if (cumQuantity < 0) {
      throw new IllegalArgumentException("cumQuantity must be >= 0: " + cumQuantity);
    }
  }
}
