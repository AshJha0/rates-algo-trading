package io.ratesalgo.api;

/**
 * Carry and roll-down per instrument from pricing, for the quoting layer: what a position earns
 * or costs to hold over the source's horizon if the curves do not move. A quote can lean on it (a
 * position that costs to hold is worth less to take on), and inventory reports can show it.
 *
 * <p>Must not allocate. Nothing in the quoting engine reads this yet; it is the interface the
 * strategy round wires in.
 */
public interface CarrySource {

  /**
   * Fills {@code out} for the instrument. Returns false for an unknown instrument or one without
   * a value yet, in which case {@code out} is cleared.
   */
  boolean carry(int instrumentId, CarryRoll out);
}
