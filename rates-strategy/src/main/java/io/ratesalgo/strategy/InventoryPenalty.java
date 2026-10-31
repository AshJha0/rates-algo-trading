package io.ratesalgo.strategy;

/**
 * Feature 6, funding and balance sheet: a cost in bp of the trade's DV01 for the funding and
 * balance-sheet use the trade adds to (or, negative, removes from) the inventory
 * (API_SPEC.md Section 19.7). The spread optimiser adds it to its other costs.
 *
 * <p>The default, {@link #NONE}, is today's engine: inventory is free to hold. Implementations are
 * allocation-free.
 */
@FunctionalInterface
public interface InventoryPenalty {

  /** Today's rule: no inventory penalty. */
  InventoryPenalty NONE = (ladder, index, weBuy, quantity) -> 0.0;

  /** Cost in bp of the trade's DV01 of buying ({@code weBuy}) or selling {@code quantity} minor units. */
  double penaltyBp(RiskLadder ladder, int index, boolean weBuy, long quantity);
}
