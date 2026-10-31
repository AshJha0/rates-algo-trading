package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.VenueExclusion;

/**
 * The venue cost model and the allocation of one slice across venues (API_SPEC.md Sections 15.1
 * and 15.2).
 *
 * <p>Per unit of quantity {@code q} on venue {@code v}, in price points relative to the venue mid,
 * with half spread {@code h}, impact {@code I(q) = y * sigmaDay * (q / ADV)^alpha} (the
 * square-root law when alpha is 0.5, as in the owner's earlier library, API_SPEC.md Section 8.3),
 * latency cost {@code L = kLat * sigmaSec * sqrt(latency)} and adverse move {@code A = kAdv *
 * sigmaSec * sqrt(T)} over the passive horizon {@code T}:
 *
 * <pre>
 * aggressive(q) = h + I(q) + takerFee + L
 * passive(q)    = P(q) (-h + I(q) + makerFee) + (1 - P(q)) (A + h + I(q) + takerFee) + L
 * chosen(q)     = min of the allowed ones (ties cross)
 * </pre>
 *
 * where {@code P(q)} is the complete-fill probability of {@link QueueFillModel} with the displayed
 * same-side depth ahead, the unit market-order rate {@code tradeRate / (2 * lot)} and the venue's
 * cancellation rate. The unfilled passive remainder escalates to aggressive at the end of {@code
 * T}, which is why its cost carries {@code A + h + takerFee}; impact is charged to both legs
 * because the square-root law measures a metaorder's volume whatever the order type (the same
 * argument as the earlier library's Section 13.2).
 *
 * <p>Allocation minimises {@code sum_v q_v * chosen_v(q_v)} subject to {@code sum_v q_v <=
 * slice}, {@code q_v = 0 or minSize_v <= q_v <= cap_v}, {@code q_v} a multiple of the lot. It is
 * greedy by marginal cost per unit in blocks of a common increment: each step gives the next block
 * to the venue whose total cost rises least per unit. For convex per-venue total costs and no
 * minimum sizes this is optimal on the lattice of increments; minimum sizes make the first block
 * of a venue larger, and then the greedy result is a heuristic (DECISIONS.md DR-P4-04). Ties go to
 * the lower row. Nothing here allocates.
 */
public final class Allocator {

  private static final double SCALE = 1e9;
  private static final double MAX_WAIT_SECONDS = 1e6;

  private final QueueFillModel queue = new QueueFillModel();

  private final long[] current = new long[CostTable.MAX_VENUES];
  private final double[] totalCost = new double[CostTable.MAX_VENUES];
  private final long[] cachedBlock = new long[CostTable.MAX_VENUES];
  private final long[] cachedFrom = new long[CostTable.MAX_VENUES];
  private final double[] cachedMarginal = new double[CostTable.MAX_VENUES];
  private final double[] cachedNextTotal = new double[CostTable.MAX_VENUES];
  private final long[] firstBlock = new long[CostTable.MAX_VENUES];

  // results of the last evaluate()
  private double fillProbability;
  private double impact;
  private double passiveCost;
  private double aggressiveCost;
  private double chosenCost;
  private boolean passiveChosen;
  private double waitSeconds;

  /**
   * Evaluates the cost per unit of placing {@code quantity} on row {@code r} of the table.
   *
   * @return the chosen cost per unit, price points
   */
  public double evaluate(
      final CostTable t, final int r, final long quantity, final RouterParams rp,
      final VenueParams vp) {
    final double halfSpread = (t.bestAsk[r] - t.bestBid[r]) * 0.5 / SCALE;
    final double sigmaSec = rp.sigmaPerSqrtSecond();
    impact =
        rp.impactY()
            * rp.sigmaDailyPoints()
            * StrictMath.pow((double) quantity / vp.adv(), rp.impactAlpha());
    final double latency =
        rp.latencyRiskFactor() * sigmaSec * StrictMath.sqrt(t.latencyNanos[r] / SCALE);
    final double taker = t.takerFee[r] / SCALE;
    final double maker = t.makerFee[r] / SCALE;
    aggressiveCost = halfSpread + impact + taker + latency;

    final double horizon = t.passiveHorizonNanos / SCALE;
    final long lot = t.lotSize[r];
    final double mu = t.tradeRate[r] / SCALE / (2.0 * lot);
    final long sizeUnits = ceilDiv(quantity, lot);
    final boolean modelled = sizeUnits < QueueFillModel.MAX_QUEUE_UNITS;
    final int size = (int) Math.min(sizeUnits, QueueFillModel.MAX_QUEUE_UNITS - 1L);
    final int ahead =
        (int) Math.min(ceilDiv(t.sameSideDepth[r], lot), QueueFillModel.MAX_QUEUE_UNITS - size);
    if (horizon > 0.0 && mu > 0.0 && modelled) {
      fillProbability = queue.probability(ahead, size, mu, vp.cancelRatePerSecond(), horizon);
      waitSeconds = Math.min((ahead + size) / mu, MAX_WAIT_SECONDS);
    } else {
      fillProbability = 0.0;
      waitSeconds = MAX_WAIT_SECONDS;
    }
    final double adverse = rp.adverseFactor() * sigmaSec * StrictMath.sqrt(horizon);
    passiveCost =
        fillProbability * (-halfSpread + impact + maker)
            + (1.0 - fillProbability) * (adverse + halfSpread + impact + taker)
            + latency;
    final boolean passiveOk = t.passiveAllowed[r] && horizon > 0.0;
    passiveChosen = passiveOk && (!t.aggressiveAllowed[r] || passiveCost < aggressiveCost);
    chosenCost = passiveChosen ? passiveCost : aggressiveCost;
    return chosenCost;
  }

  /**
   * Allocates {@code t.sliceQuantity} across the table's rows and fills every output field.
   *
   * @param vps venue parameters in row order
   */
  public void allocate(final CostTable t, final RouterParams rp, final VenueParams[] vps) {
    final int n = t.venueCount;
    long increment = 0;
    for (int r = 0; r < n; r++) {
      increment = Math.max(increment, t.lotSize[r]);
    }
    final long slice = t.sliceQuantity;
    final long steps = increment == 0 ? 0 : slice / increment;
    if (steps > rp.maxSteps()) {
      increment = increment * ceilDiv(steps, rp.maxSteps());
    }

    for (int r = 0; r < n; r++) {
      current[r] = 0;
      totalCost[r] = 0.0;
      cachedBlock[r] = -1;
      cachedFrom[r] = -1;
      if (t.exclusion[r] != VenueExclusion.None) {
        continue;
      }
      final long first = roundUp(Math.max(t.minSize[r], increment), t.lotSize[r]);
      firstBlock[r] = first;
      if (first > t.participationCap[r]) {
        t.exclusion[r] = VenueExclusion.CapReached;
      } else if (first > slice) {
        t.exclusion[r] = VenueExclusion.BelowMinimum;
      }
    }

    long remaining = slice;
    while (remaining > 0) {
      int best = -1;
      double bestMarginal = Double.POSITIVE_INFINITY;
      for (int r = 0; r < n; r++) {
        if (t.exclusion[r] != VenueExclusion.None) {
          continue;
        }
        final long block = nextBlock(t, r, increment, remaining);
        if (block <= 0) {
          continue;
        }
        if (cachedBlock[r] != block || cachedFrom[r] != current[r]) {
          final long to = current[r] + block;
          final double nextTotal = to * evaluate(t, r, to, rp, vps[r]);
          cachedNextTotal[r] = nextTotal;
          cachedMarginal[r] = (nextTotal - totalCost[r]) / block;
          cachedBlock[r] = block;
          cachedFrom[r] = current[r];
        }
        if (cachedMarginal[r] < bestMarginal) {
          bestMarginal = cachedMarginal[r];
          best = r;
        }
      }
      if (best < 0) {
        break;
      }
      current[best] += cachedBlock[best];
      totalCost[best] = cachedNextTotal[best];
      remaining -= cachedBlock[best];
    }

    long allocatedTotal = 0;
    double weighted = 0.0;
    for (int r = 0; r < n; r++) {
      t.allocated[r] = current[r];
      final boolean eligible =
          t.exclusion[r] == VenueExclusion.None
              || t.exclusion[r] == VenueExclusion.BelowMinimum
              || t.exclusion[r] == VenueExclusion.CapReached;
      if (!eligible) {
        clearRow(t, r);
        continue;
      }
      final long q = current[r] > 0 ? current[r] : firstBlock[r];
      evaluate(t, r, q, rp, vps[r]);
      t.evaluatedQuantity[r] = q;
      t.passive[r] = passiveChosen;
      t.fillProbability[r] = Math.round(fillProbability * SCALE);
      t.queueWaitNanos[r] = Math.round(waitSeconds * SCALE);
      t.impactCost[r] = Math.round(impact * SCALE);
      t.passiveCost[r] = Math.round(passiveCost * SCALE);
      t.aggressiveCost[r] = Math.round(aggressiveCost * SCALE);
      t.chosenCost[r] = Math.round(chosenCost * SCALE);
      allocatedTotal += current[r];
      weighted += current[r] * chosenCost;
    }
    t.allocatedQuantity = allocatedTotal;
    t.expectedCost = allocatedTotal == 0 ? 0 : Math.round(weighted / allocatedTotal * SCALE);
  }

  private long nextBlock(
      final CostTable t, final int r, final long increment, final long remaining) {
    final long lot = t.lotSize[r];
    long block;
    if (current[r] == 0) {
      block = firstBlock[r];
      if (block > remaining) {
        return 0;
      }
    } else {
      block = Math.min(increment, remaining - remaining % lot);
    }
    final long room = t.participationCap[r] - current[r];
    if (block > room) {
      if (current[r] == 0) {
        return 0;
      }
      block = room - room % lot;
    }
    return block;
  }

  private static void clearRow(final CostTable t, final int r) {
    t.evaluatedQuantity[r] = 0;
    t.allocated[r] = 0;
    t.passive[r] = false;
    t.fillProbability[r] = 0;
    t.queueWaitNanos[r] = 0;
    t.impactCost[r] = 0;
    t.passiveCost[r] = 0;
    t.aggressiveCost[r] = 0;
    t.chosenCost[r] = 0;
  }

  static long ceilDiv(final long a, final long b) {
    return -Math.floorDiv(-a, b);
  }

  static long roundUp(final long a, final long multiple) {
    return ceilDiv(a, multiple) * multiple;
  }
}
