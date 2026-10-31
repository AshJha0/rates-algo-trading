package io.ratesalgo.venues.d2c;

import io.ratesalgo.venues.sim.Px;
import io.ratesalgo.venues.sim.SimRng;
import java.math.BigDecimal;
import java.util.List;

/** A discrete size distribution: sizes in currency units with weights. */
public final class SizePreset {

  private final long[] sizes;
  private final double[] weights;

  private SizePreset(final long[] sizes, final double[] weights) {
    this.sizes = sizes;
    this.weights = weights;
  }

  /** From a JSON list of {@code [size, weight]} pairs. */
  static SizePreset from(final List<Object> pairs) {
    final long[] s = new long[pairs.size()];
    final double[] w = new double[pairs.size()];
    for (int i = 0; i < pairs.size(); i++) {
      final List<?> pair = (List<?>) pairs.get(i);
      s[i] = Px.qty(pair.get(0).toString());
      w[i] = ((BigDecimal) pair.get(1)).doubleValue();
    }
    return new SizePreset(s, w);
  }

  /** Draws a size. */
  public long draw(final SimRng rng) {
    return sizes[rng.discrete(weights)];
  }

  /** Number of size buckets. */
  public int buckets() {
    return sizes.length;
  }
}
