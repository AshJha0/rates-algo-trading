package io.ratesalgo.hedge;

import org.agrona.collections.Int2IntHashMap;

/**
 * Hedge cost model from a table: half spread, basis cost and impact per instrument. Raising an
 * instrument's basis cost makes the planner prefer the others. Reads do not allocate.
 */
public final class TableHedgeCostModel implements HedgeCostModel {

  private final Int2IntHashMap slots;
  private final double[] halfSpread;
  private final double[] basis;
  private final double[] impact;

  /** A table for up to {@code capacity} instruments. */
  public TableHedgeCostModel(final int capacity) {
    slots = new Int2IntHashMap(capacity * 2, 0.5f, -1);
    halfSpread = new double[capacity];
    basis = new double[capacity];
    impact = new double[capacity];
  }

  /** Sets every cost for one instrument; all values in major currency units per lot. */
  public TableHedgeCostModel set(
      final int instrumentId,
      final double halfSpreadPerLot,
      final double basisPerLot,
      final double impactPerLot2) {
    if (halfSpreadPerLot < 0.0 || basisPerLot < 0.0 || impactPerLot2 < 0.0) {
      throw new IllegalArgumentException(
          "negative cost for " + instrumentId + ": " + halfSpreadPerLot + ", " + basisPerLot + ", " + impactPerLot2);
    }
    final int s = slot(instrumentId);
    halfSpread[s] = halfSpreadPerLot;
    basis[s] = basisPerLot;
    impact[s] = impactPerLot2;
    return this;
  }

  /** Changes only the basis cost of one instrument. */
  public TableHedgeCostModel basis(final int instrumentId, final double basisPerLot) {
    if (basisPerLot < 0.0) {
      throw new IllegalArgumentException("negative basis cost " + basisPerLot);
    }
    basis[slot(instrumentId)] = basisPerLot;
    return this;
  }

  @Override
  public double linearPerLot(final int instrumentId) {
    final int s = slots.get(instrumentId);
    return s < 0 ? Double.MAX_VALUE / 1e6 : halfSpread[s] + basis[s];
  }

  @Override
  public double impactPerLot2(final int instrumentId) {
    final int s = slots.get(instrumentId);
    return s < 0 ? 0.0 : impact[s];
  }

  private int slot(final int instrumentId) {
    int s = slots.get(instrumentId);
    if (s < 0) {
      s = slots.size();
      if (s >= halfSpread.length) {
        throw new IllegalStateException("cost table capacity " + halfSpread.length + " exhausted");
      }
      slots.put(instrumentId, s);
    }
    return s;
  }
}
