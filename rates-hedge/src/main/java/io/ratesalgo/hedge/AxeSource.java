package io.ratesalgo.hedge;

import io.ratesalgo.messages.sbe.Side;

/** The hedger's wishes as seen by the quoting engine: how much to improve a side, in bp. */
@FunctionalInterface
public interface AxeSource {

  /** No axes. */
  AxeSource NONE = (instrumentId, ourSide) -> 0.0;

  /**
   * Basis points of value by which to improve our price on {@code ourSide} of {@code
   * instrumentId} because trading that side moves the book toward the hedger's target. Never
   * negative; allocation-free.
   */
  double axeBp(int instrumentId, Side ourSide);
}
