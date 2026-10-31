package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;

/**
 * Where child orders go. The coordinator binds this to the P2 venue gateway, which encodes a
 * NewOrder (template 20) and a venue cancel; results come back through {@link
 * ExecutionListener}. Calls must not allocate.
 */
public interface OrderGateway {

  /** Sends a new child order. Fields as SBE NewOrder (API_SPEC.md Section 2). */
  void newOrder(
      int venueId,
      long clOrdId,
      long parentId,
      int instrumentId,
      Side side,
      OrdType ordType,
      TimeInForce timeInForce,
      long price,
      long quantity);

  /** Requests cancellation of a resting child order. */
  void cancel(int venueId, long clOrdId);
}
