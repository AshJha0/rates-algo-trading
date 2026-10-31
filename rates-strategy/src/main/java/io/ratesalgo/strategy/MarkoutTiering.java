package io.ratesalgo.strategy;

import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.quoting.TierTableControl;
import io.ratesalgo.quoting.ToxicityTable;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.agrona.collections.Int2IntHashMap;

/**
 * Feature 4: automatic client tiering from rolling markouts, with hysteresis and a minimum
 * sample size, published through the existing tier table's config path ({@link
 * TierTableControl}: propose by one id, approve by another).
 *
 * <pre>
 *   m(c)  = exponentially weighted mean adverse markout of client c, bp      ({@link ToxicityTable})
 *   n(c)  = fills observed
 *   tier index t (0 best):
 *     n &gt;= minSample and m &lt; demoteBelow[t]     t = t + 1    (worse tier)
 *     n &gt;= minSample and m &gt; promoteAbove[t]    t = t - 1    (better tier)
 * </pre>
 *
 * with {@code promoteAbove[t] > demoteBelow[t - 1]} so a client sitting on a boundary does not
 * flap. Unknown clients start at {@code startIndex}. The learned state is meant to persist across
 * sessions (the loop is rolling); the experiment carries one instance through the days in order.
 */
public final class MarkoutTiering implements ClientTiering {

  private final TierTableControl control;
  private final List<ClientTier> tiers;
  private final ToxicityTable markouts;
  private final Int2IntHashMap assigned;
  private final double[] demoteBelow;
  private final double[] promoteAbove;
  private final long minSample;
  private final int startIndex;
  private final long reviewIntervalNanos;
  private final int proposerId;
  private final int approverId;
  private boolean dirty;
  private long lastReview = Long.MIN_VALUE;
  private long versionsPublished;

  /**
   * @param tiers tier rows, best first; the tier table is built from them
   * @param demoteBelow per tier index, demote when the mean is below (last entry unused)
   * @param promoteAbove per tier index, promote when the mean is above (first entry unused)
   * @param capacity clients tracked
   */
  public MarkoutTiering(
      final List<ClientTier> tiers,
      final double[] demoteBelow,
      final double[] promoteAbove,
      final long minSample,
      final double alpha,
      final int startIndex,
      final long reviewIntervalNanos,
      final int proposerId,
      final int approverId,
      final int capacity) {
    if (tiers.size() < 2 || demoteBelow.length != tiers.size() || promoteAbove.length != tiers.size()
        || minSample < 1 || startIndex < 0 || startIndex >= tiers.size() || reviewIntervalNanos < 0
        || proposerId == approverId) {
      throw new IllegalArgumentException("invalid tiering parameters");
    }
    for (int t = 1; t < tiers.size(); t++) {
      if (!(promoteAbove[t] > demoteBelow[t - 1])) {
        throw new IllegalArgumentException(
            "hysteresis needs promoteAbove[" + t + "] > demoteBelow[" + (t - 1) + "]");
      }
    }
    this.tiers = List.copyOf(tiers);
    this.demoteBelow = demoteBelow.clone();
    this.promoteAbove = promoteAbove.clone();
    this.minSample = minSample;
    this.startIndex = startIndex;
    this.reviewIntervalNanos = reviewIntervalNanos;
    this.proposerId = proposerId;
    this.approverId = approverId;
    this.markouts = new ToxicityTable(capacity, alpha, 0.0);
    this.assigned = new Int2IntHashMap(capacity * 2, 0.5f, -1);
    this.control = new TierTableControl(new TierTable(1L, this.tiers, Map.of(), startIndex));
  }

  @Override
  public int tier(final int clientId) {
    return control.active().tierOf(clientId).tier();
  }

  @Override
  public boolean wantsMarkouts() {
    return true;
  }

  @Override
  public void onMarkout(final int clientId, final double markoutBp, final long nowNanos) {
    markouts.record(clientId, markoutBp);
    if (markouts.observations(clientId) < minSample) {
      return;
    }
    final int current = assigned.get(clientId);
    final int t = current < 0 ? startIndex : current;
    final double m = markouts.meanMarkoutBp(clientId);
    int next = t;
    if (t < tiers.size() - 1 && m < demoteBelow[t]) {
      next = t + 1;
    } else if (t > 0 && m > promoteAbove[t]) {
      next = t - 1;
    }
    if (next != t || current < 0) {
      assigned.put(clientId, next);
      dirty |= next != t;
    }
  }

  @Override
  public void review(final long nowNanos) {
    if (!dirty || (lastReview != Long.MIN_VALUE && nowNanos - lastReview < reviewIntervalNanos)) {
      return;
    }
    final Map<Integer, Integer> map = new TreeMap<>();
    assigned.forEach(map::put);
    final long version = control.active().version() + 1;
    control.propose(new TierTable(version, tiers, map, startIndex), proposerId);
    control.approve(version, approverId);
    dirty = false;
    lastReview = nowNanos;
    versionsPublished++;
  }

  /** The control-plane object holding the active table. */
  public TierTableControl control() {
    return control;
  }

  public long versionsPublished() {
    return versionsPublished;
  }

  /** Mean markout of a client, bp. */
  public double meanMarkoutBp(final int clientId) {
    return markouts.meanMarkoutBp(clientId);
  }
}
