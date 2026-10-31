package io.ratesalgo.strategy;

/**
 * Feature 5, hedge timing: once the {@link HedgeBudget} says how much to hedge, whether to hedge
 * it now, in slices, or to wait for offsetting client flow (API_SPEC.md Section 19.6).
 *
 * <p>The default, {@link #ALWAYS_IMMEDIATE}, is today's engine. Implementations are
 * allocation-free.
 */
public interface HedgeTiming {

  /** What to do with the excess DV01. */
  enum Action {
    /** Send the whole hedge now. */
    IMMEDIATE,
    /** Send {@code 1 / slices()} of it now and ask again after {@code sliceIntervalNanos()}. */
    STAGED,
    /** Send nothing and ask again after {@code waitNanos()}. */
    WAIT
  }

  /** Today's rule: always hedge at once. */
  HedgeTiming ALWAYS_IMMEDIATE = (excessDv01, nowNanos) -> Action.IMMEDIATE;

  /** Decides for an excess of {@code excessDv01} (signed, as {@link HedgeBudget#excessDv01}). */
  Action decide(double excessDv01, long nowNanos);

  /** Observes a client fill that changed net DV01 by {@code tradeDv01}. */
  default void onClientFill(final double tradeDv01, final long nowNanos) {}

  /** Forgets any wait in progress (a hedge was sent, or nothing is left to hedge). */
  default void reset() {}

  default long waitNanos() {
    return 0L;
  }

  default int slices() {
    return 1;
  }

  default long sliceIntervalNanos() {
    return 0L;
  }
}
