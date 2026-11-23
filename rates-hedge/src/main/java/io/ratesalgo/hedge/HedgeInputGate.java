package io.ratesalgo.hedge;

/**
 * Whether the inputs a hedge order is priced from can be trusted at a given time. The quoting
 * module's InputHealth is one (heartbeats, venue sessions, feed gaps); rates-hedge cannot see that
 * class, so the desk hands it in through this interface. Calls must not allocate.
 */
@FunctionalInterface
public interface HedgeInputGate {

  /** No gate: always healthy. */
  HedgeInputGate ALWAYS = nowNanos -> true;

  /** True when hedge orders may be priced and sent at {@code nowNanos}. */
  boolean healthy(long nowNanos);
}
