package io.ratesalgo.posttrade.recovery;

/**
 * The quoting engine's on and off switch as the restart procedure needs it. P3 binds this at
 * integration; the control plane's kill switch disables it by the same route.
 */
public interface QuotingControl {

  /** Stops new quotes and orders. Idempotent. */
  void disable(String reason);

  /** Allows quoting again. Called only by the restart procedure or an authorised operator. */
  void enable(String reason);

  /** True if quoting is enabled. */
  boolean enabled();
}
