package io.ratesalgo.hedge;

import java.util.Arrays;

/** The instruments the hedger may trade, each with a lot size and a maximum number of lots. */
public final class HedgeUniverse {

  private int[] ids = new int[4];
  private long[] lotMinor = new long[4];
  private double[] maxLots = new double[4];
  private int size;

  /** Adds a hedge instrument. */
  public HedgeUniverse add(final int instrumentId, final long lotSizeMinor, final double maximumLots) {
    if (lotSizeMinor <= 0 || !(maximumLots > 0.0)) {
      throw new IllegalArgumentException("lot " + lotSizeMinor + ", max lots " + maximumLots);
    }
    if (size == ids.length) {
      ids = Arrays.copyOf(ids, size * 2);
      lotMinor = Arrays.copyOf(lotMinor, size * 2);
      maxLots = Arrays.copyOf(maxLots, size * 2);
    }
    ids[size] = instrumentId;
    lotMinor[size] = lotSizeMinor;
    maxLots[size] = maximumLots;
    size++;
    return this;
  }

  public int size() {
    return size;
  }

  public int instrumentId(final int j) {
    return ids[j];
  }

  public long lotMinor(final int j) {
    return lotMinor[j];
  }

  public double maxLots(final int j) {
    return maxLots[j];
  }

  /** Position of an instrument in this universe, or -1. */
  public int indexOf(final int instrumentId) {
    for (int j = 0; j < size; j++) {
      if (ids[j] == instrumentId) {
        return j;
      }
    }
    return -1;
  }
}
