package io.ratesalgo.api;

import io.ratesalgo.messages.sbe.Side;

/**
 * Parent hedge orders from the hedger to the smart order router (P4). The router slices a parent
 * into child NewOrder messages, each of which passes the risk engine's pre-trade checks again.
 * Calls must not allocate. {@link RecordingHedgeOrderSink} is the stub.
 */
public interface HedgeOrderSink {

  /**
   * Submits a parent order. {@code limitPrice} is a Decimal9 mantissa beyond which the router must
   * not trade; {@code urgencyNanos} is the time by which the hedger wants it done (0 means
   * immediately, as aggressively as the limit allows). Returns false if the router refuses it.
   */
  boolean submit(
      long parentId,
      int instrumentId,
      Side side,
      long quantityMinor,
      long limitPrice,
      long urgencyNanos,
      long sentNanos);

  /** Cancels the unfilled remainder of a parent order. */
  void cancel(long parentId);

  /** Cancels every parent order this sink has accepted; used by the kill switch. */
  void cancelAll();
}
