package io.ratesalgo.quoting;

import org.agrona.collections.Int2IntHashMap;

/**
 * Historical markout per client: an exponentially weighted mean of the P&amp;L, in bp of value
 * from our side, of each of our fills with that client measured a fixed horizon after the fill. A
 * negative mean means the client trades ahead of moves against us. The adverse-selection charge is
 * the negative part, shrunk toward zero for clients with few observations: {@code max(0, -mean) *
 * n / (n + priorCount)}. Allocation-free once a client has a slot.
 */
public final class ToxicityTable {

  private final Int2IntHashMap slots;
  private final double[] mean;
  private final long[] count;
  private final double alpha;
  private final double priorCount;

  /** A table for up to {@code capacity} clients. */
  public ToxicityTable(final int capacity, final double alpha, final double priorCount) {
    if (!(alpha > 0.0 && alpha <= 1.0) || priorCount < 0.0) {
      throw new IllegalArgumentException("alpha " + alpha + ", prior " + priorCount);
    }
    slots = new Int2IntHashMap(capacity * 2, 0.5f, -1);
    mean = new double[capacity];
    count = new long[capacity];
    this.alpha = alpha;
    this.priorCount = priorCount;
  }

  /** Records a markout in bp for a client. Clients beyond capacity are ignored. */
  public void record(final int clientId, final double markoutBp) {
    int s = slots.get(clientId);
    if (s < 0) {
      if (slots.size() == mean.length) {
        return;
      }
      s = slots.size();
      slots.put(clientId, s);
    }
    mean[s] = count[s] == 0 ? markoutBp : mean[s] + alpha * (markoutBp - mean[s]);
    count[s]++;
  }

  /** Adverse-selection charge for a client, in bp; 0 for unknown clients. */
  public double adverseBp(final int clientId) {
    final int s = slots.get(clientId);
    if (s < 0 || mean[s] >= 0.0) {
      return 0.0;
    }
    return -mean[s] * count[s] / (count[s] + priorCount);
  }

  /** Mean markout, bp, or 0. */
  public double meanMarkoutBp(final int clientId) {
    final int s = slots.get(clientId);
    return s < 0 ? 0.0 : mean[s];
  }

  /** Observations for a client. */
  public long observations(final int clientId) {
    final int s = slots.get(clientId);
    return s < 0 ? 0L : count[s];
  }
}
