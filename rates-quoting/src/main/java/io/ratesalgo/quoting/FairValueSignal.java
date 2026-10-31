package io.ratesalgo.quoting;

/**
 * An additive adjustment to fair value, in bp of value (positive raises the fair quote). The
 * default is zero. A non-zero signal may only be installed with a {@link ValidationRecord} that
 * passes {@link ValidationRecord#permits(long)} (API_SPEC.md Section 12.5; DR-P3-03).
 */
@FunctionalInterface
public interface FairValueSignal {

  /** The zero signal. */
  FairValueSignal NONE = (instrumentId, nowNanos) -> 0.0;

  /** Adjustment in bp of value; must not allocate. */
  double adjustmentBp(int instrumentId, long nowNanos);
}
