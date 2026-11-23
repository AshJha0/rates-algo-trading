package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.VenueExclusion;

/**
 * The ladder model's allocation of one slice (API_SPEC.md Section 15.1, DECISIONS.md DR-R6-R01
 * and DR-R6-R02). It reads only the {@link CostTable}, so a decision journaled with its
 * RouteDecisionModel and RouteDecisionLadder frames is re-evaluated to the same values.
 *
 * <p><b>Aggressive leg.</b> Every opposite level of every usable venue, down to the row's limit
 * price (the collar of {@code aggressiveCollarTicks} past the far touch, the parent limit and one
 * tick inside our own orders), becomes an entry with its displayed size less our own resting size
 * and its all-in price per unit relative to the consolidated mid {@code m}:
 *
 * <pre>
 * c(level) = s * (price - m) + takerFee + penalty + kLat * sigmaSec * sqrt(latency)
 * </pre>
 *
 * with {@code s} +1 for a buy and -1 for a sell and {@code penalty} the venue scorecard's (below).
 * Entries are swept cheapest first, each venue's take bounded by its participation cap and
 * rounded down to its lot; a venue whose take would fall below its minimum size is dropped and
 * the sweep repeated, and the total is rounded down to the decision increment. A venue's child is
 * sized to what it takes and limited at the last level it needs. The expected cost of crossing
 * {@code X} is the walk itself plus the post-trade part of the square-root impact:
 *
 * <pre>
 * aggressive(X) = (sum of q(level) c(level) + M per child) / X + share * y * sigmaDay * (X / ADV)^alpha
 * </pre>
 *
 * <p><b>Passive leg.</b> A child joining the touch on venue {@code v} for horizon {@code T}
 * fills completely with probability {@code P}: the queue model of {@link QueueFillModel} with the
 * displayed queue ahead, the market order rate against our side measured from trade prints and
 * the cancellation rate learned from touch decrements, times the calibration factor of {@link
 * FillCalibration}. Its expected time open is {@code T_open = P min(W, T) + (1 - P) T}, and it
 * is charged the variance of the mid over that time:
 *
 * <pre>
 * passive(q) = o + penalty + P (-h + makerFee + F) + (1 - P) (A + crossing + 2M) + L + M
 *              + lambda * sigmaSec^2 * T_open * q / 1e8 + share * I(q)
 * </pre>
 *
 * where {@code crossing} is the per-unit walk of the aggressive leg for the slice (what the
 * remainder would pay at the time limit). The passive split across venues is greedy by marginal
 * cost, as in the touch model. The slice goes passive if its passive cost per unit is below the
 * aggressive one, otherwise it is swept; ties cross.
 *
 * <p><b>Penalty.</b> {@code rejectPenalty * rejectRate + cancelRejectPenalty * cancelRejectRate
 * + fillShortfallPenalty * (1 - fillRatio) + markoutPenaltyWeight * max(0, -markout5s)}, from
 * the scorecard values journaled with the row. Nothing here allocates.
 */
public final class LadderAllocator {

  private static final double SCALE = 1e9;
  private static final double MAX_WAIT_SECONDS = 1e6;
  private static final int ENTRIES = CostTable.MAX_VENUES * CostTable.MAX_LEVELS;
  private static final double MAX_UNIFORMISED = 150_000.0;

  private final QueueFillModel queue = new QueueFillModel();

  private final int[] eRow = new int[ENTRIES];
  private final int[] eLevel = new int[ENTRIES];
  private final long[] ePrice = new long[ENTRIES];
  private final long[] eAvail = new long[ENTRIES];
  private final double[] eCost = new double[ENTRIES];
  private final int[] order = new int[ENTRIES];
  private int entries;

  private final long[] taken = new long[CostTable.MAX_VENUES];
  private final double[] walk = new double[CostTable.MAX_VENUES];
  private final long[] last = new long[CostTable.MAX_VENUES];
  private final boolean[] blocked = new boolean[CostTable.MAX_VENUES];
  private final double[] pen = new double[CostTable.MAX_VENUES];
  private final double[] bestEntry = new double[CostTable.MAX_VENUES];

  private final long[] pq = new long[CostTable.MAX_VENUES];
  private final double[] pTotal = new double[CostTable.MAX_VENUES];
  private final long[] firstBlock = new long[CostTable.MAX_VENUES];

  private final double[] modelP = new double[CostTable.MAX_VENUES];

  // results of the last passiveUnit()
  private double pModel;
  private double pFill;
  private double pWait;
  private double pRisk;

  private double fallbackUnit;
  private double advTotal;

  /**
   * Allocates {@code t.sliceQuantity} and fills every output field.
   *
   * @param vps venue parameters in row order
   */
  public void allocate(
      final CostTable t, final RouterParams rp, final VenueParams[] vps, final ExecutionModel m) {
    final int n = t.venueCount;
    final double sign = t.side == Side.Sell ? -1.0 : 1.0;
    final double sigmaSec = rp.sigmaPerSqrtSecond(t.instrumentId);
    final double horizon = t.passiveHorizonNanos / SCALE;
    long increment = 0;
    for (int r = 0; r < n; r++) {
      increment = Math.max(increment, t.lotSize[r]);
    }
    final long slice = t.sliceQuantity;
    long bestBid = 0;
    long bestAsk = Long.MAX_VALUE;
    advTotal = 0.0;
    fallbackUnit = Double.NaN;
    entries = 0;
    for (int r = 0; r < n; r++) {
      clearRow(t, r);
      modelP[r] = 0.0;
      blocked[r] = false;
      taken[r] = 0;
      walk[r] = 0.0;
      last[r] = 0;
      pq[r] = 0;
      pTotal[r] = 0.0;
      pen[r] = 0.0;
      bestEntry[r] = Double.NaN;
      if (t.exclusion[r] != VenueExclusion.None) {
        continue;
      }
      pen[r] = penalty(t, r, m);
      t.penalty[r] = Math.round(pen[r] * SCALE);
      firstBlock[r] = Allocator.roundUp(Math.max(t.minSize[r], t.lotSize[r]), t.lotSize[r]);
      if (firstBlock[r] > t.participationCap[r]) {
        t.exclusion[r] = VenueExclusion.CapReached;
        continue;
      }
      advTotal += vps[r].adv();
      if (t.bestBid[r] > 0 && t.bestAsk[r] > t.bestBid[r]) {
        bestBid = Math.max(bestBid, t.bestBid[r]);
        bestAsk = Math.min(bestAsk, t.bestAsk[r]);
      }
    }
    if (bestBid == 0 || bestAsk == Long.MAX_VALUE || slice <= 0) {
      finish(t, n, false, 0.0, 0.0, m, rp, sigmaSec, horizon, 0L);
      return;
    }
    final long mid2 = bestBid + bestAsk;
    buildEntries(t, rp, sigmaSec, sign, mid2);

    // the sweep, repeated without a venue whose share would be below its minimum, and rounded to
    // the decision increment so the remainder stays placeable
    long target = slice;
    long total = 0;
    for (int iter = 0; iter < 2 * n + 8; iter++) {
      total = sweep(t, target);
      int bad = -1;
      for (int r = 0; r < n; r++) {
        if (taken[r] > 0 && taken[r] < t.minSize[r]) {
          bad = r;
          break;
        }
      }
      if (bad >= 0) {
        blocked[bad] = true;
        continue;
      }
      final long excess = total % increment;
      if (excess != 0) {
        target = total - excess;
        if (target <= 0) {
          total = sweep(t, 0);
          break;
        }
        continue;
      }
      break;
    }
    double walkTotal = 0.0;
    double messages = 0.0;
    for (int r = 0; r < n; r++) {
      if (taken[r] > 0) {
        walkTotal += walk[r];
        messages += rp.messageCostPoints() * t.lotSize[r];
      }
    }
    final double aggUnit =
        total > 0 ? (walkTotal + messages) / total + impactUnit(rp, m, t, total) : Double.POSITIVE_INFINITY;
    fallbackUnit = total > 0 ? (walkTotal + messages) / total : Double.NaN;

    // the passive split
    double passiveTotal = 0.0;
    long passiveQty = 0;
    if (horizon > 0.0) {
      passiveQty = passiveSplit(t, rp, m, sigmaSec, horizon, increment, slice);
      for (int r = 0; r < n; r++) {
        passiveTotal += pTotal[r];
      }
    }
    final boolean passive =
        passiveQty > 0 && (total == 0 || passiveTotal / passiveQty < aggUnit);
    finish(t, n, passive, aggUnit, passiveQty > 0 ? passiveTotal / passiveQty : 0.0, m, rp,
        sigmaSec, horizon, total);
  }

  /** The scorecard penalty of a row, price points per unit. */
  static double penalty(final CostTable t, final int r, final ExecutionModel m) {
    final double markout = t.markout5s[r] / SCALE;
    return m.rejectPenalty() * (t.rejectRate[r] / SCALE)
        + m.cancelRejectPenalty() * (t.cancelRejectRate[r] / SCALE)
        + m.fillShortfallPenalty() * Math.max(0.0, 1.0 - t.fillRatio[r] / SCALE)
        + m.markoutPenaltyWeight() * Math.max(0.0, -markout);
  }

  private void buildEntries(
      final CostTable t, final RouterParams rp, final double sigmaSec, final double sign,
      final long mid2) {
    entries = 0;
    final boolean buy = sign > 0;
    for (int r = 0; r < t.venueCount; r++) {
      if (t.exclusion[r] != VenueExclusion.None || !t.aggressiveAllowed[r]) {
        continue;
      }
      final double fixed =
          t.takerFee[r] / SCALE
              + pen[r]
              + rp.latencyRiskFactor() * sigmaSec * StrictMath.sqrt(t.latencyNanos[r] / SCALE);
      final long lot = t.lotSize[r];
      for (int k = 0; k < t.levelCount[r]; k++) {
        final int i = r * CostTable.MAX_LEVELS + k;
        final long px = t.levelPrice[i];
        if (px <= 0 || (buy ? px > t.limitPrice[r] : px < t.limitPrice[r])) {
          break;
        }
        long avail = Math.max(0L, t.levelDisplayed[i] - t.levelOwn[i]);
        avail -= avail % lot;
        final double cost = sign * (2 * px - mid2) * 0.5 / SCALE + fixed;
        if (Double.isNaN(bestEntry[r])) {
          bestEntry[r] = cost;
        }
        if (avail <= 0) {
          continue;
        }
        eRow[entries] = r;
        eLevel[entries] = k;
        ePrice[entries] = px;
        eAvail[entries] = avail;
        eCost[entries] = cost;
        // insertion by (cost, row, level): the cheapest all-in price first, ties to the lower row
        int j = entries;
        while (j > 0 && before(entries, order[j - 1])) {
          order[j] = order[j - 1];
          j--;
        }
        order[j] = entries;
        entries++;
      }
    }
  }

  private boolean before(final int a, final int b) {
    if (eCost[a] != eCost[b]) {
      return eCost[a] < eCost[b];
    }
    if (eRow[a] != eRow[b]) {
      return eRow[a] < eRow[b];
    }
    return eLevel[a] < eLevel[b];
  }

  private long sweep(final CostTable t, final long target) {
    for (int r = 0; r < t.venueCount; r++) {
      taken[r] = 0;
      walk[r] = 0.0;
      last[r] = 0;
    }
    long remaining = target;
    for (int x = 0; x < entries && remaining > 0; x++) {
      final int e = order[x];
      final int r = eRow[e];
      if (blocked[r]) {
        continue;
      }
      final long lot = t.lotSize[r];
      long take = Math.min(eAvail[e], Math.min(remaining, t.participationCap[r] - taken[r]));
      take -= take % lot;
      if (take <= 0) {
        continue;
      }
      taken[r] += take;
      walk[r] += take * eCost[e];
      last[r] = ePrice[e];
      remaining -= take;
    }
    return target - remaining;
  }

  private double impactUnit(
      final RouterParams rp, final ExecutionModel m, final CostTable t, final long q) {
    if (advTotal <= 0.0 || q <= 0) {
      return 0.0;
    }
    return m.postTradeImpactShare()
        * rp.impactY()
        * rp.sigmaDailyPoints(t.instrumentId)
        * StrictMath.pow(q / advTotal, rp.impactAlpha());
  }

  private long passiveSplit(
      final CostTable t, final RouterParams rp, final ExecutionModel m, final double sigmaSec,
      final double horizon, final long increment, final long slice) {
    final int n = t.venueCount;
    long remaining = slice;
    while (remaining > 0) {
      int best = -1;
      double bestMarginal = Double.POSITIVE_INFINITY;
      long bestBlock = 0;
      double bestNext = 0.0;
      for (int r = 0; r < n; r++) {
        if (t.exclusion[r] != VenueExclusion.None || !t.passiveAllowed[r]) {
          continue;
        }
        final long lot = t.lotSize[r];
        long block = pq[r] == 0 ? firstBlock[r] : Math.min(increment, remaining - remaining % lot);
        if (pq[r] == 0 && block > remaining) {
          continue;
        }
        final long room = t.participationCap[r] - pq[r];
        if (block > room) {
          if (pq[r] == 0) {
            continue;
          }
          block = room - room % lot;
        }
        if (block <= 0) {
          continue;
        }
        final long to = pq[r] + block;
        final double next = to * passiveUnit(t, r, to, rp, m, sigmaSec, horizon);
        final double marginal = (next - pTotal[r]) / block;
        if (marginal < bestMarginal) {
          bestMarginal = marginal;
          best = r;
          bestBlock = block;
          bestNext = next;
        }
      }
      if (best < 0) {
        break;
      }
      pq[best] += bestBlock;
      pTotal[best] = bestNext;
      remaining -= bestBlock;
    }
    final long placed = slice - remaining;
    // rounded to the decision increment, as the sweep is, so the remainder stays placeable
    long excess = placed % increment;
    for (int r = n - 1; r >= 0 && excess > 0; r--) {
      if (pq[r] == 0) {
        continue;
      }
      final long lot = t.lotSize[r];
      long cut = Math.min(excess, pq[r]);
      cut = Allocator.roundUp(cut, lot);
      cut = Math.min(cut, pq[r]);
      if (pq[r] - cut > 0 && pq[r] - cut < firstBlock[r]) {
        cut = pq[r];
      }
      pq[r] -= cut;
      excess -= cut;
      pTotal[r] = pq[r] == 0 ? 0.0 : pq[r] * passiveUnit(t, r, pq[r], rp, m, sigmaSec, horizon);
    }
    long sum = 0;
    for (int r = 0; r < n; r++) {
      sum += pq[r];
    }
    if (sum % increment != 0) {
      // a lot mix the trim could not round: nothing passive this time
      for (int r = 0; r < n; r++) {
        pq[r] = 0;
        pTotal[r] = 0.0;
      }
      return 0;
    }
    return sum;
  }

  /** Passive cost per unit of {@code q} on row {@code r}; sets pFill, pWait and pRisk. */
  double passiveUnit(
      final CostTable t, final int r, final long q, final RouterParams rp, final ExecutionModel m,
      final double sigmaSec, final double horizon) {
    final double halfSpread = (t.bestAsk[r] - t.bestBid[r]) * 0.5 / SCALE;
    final double offset = Allocator.venueOffset(t, r);
    final long lot = t.lotSize[r];
    final double mu = t.tradeRate[r] / SCALE / lot;
    final double theta = t.cancelRate[r] / SCALE;
    final long sizeUnits = Allocator.ceilDiv(q, lot);
    final long aheadUnits = Allocator.ceilDiv(t.sameSideDepth[r], lot);
    double model = 0.0;
    pWait = MAX_WAIT_SECONDS;
    if (horizon > 0.0 && mu > 0.0 && aheadUnits + sizeUnits < QueueFillModel.MAX_QUEUE_UNITS) {
      final double lambda = mu + theta * aheadUnits;
      if (lambda * horizon > MAX_UNIFORMISED) {
        // so many orders arrive within the horizon that the queue clears
        model = 1.0;
      } else {
        model = queue.probability((int) aheadUnits, (int) sizeUnits, mu, theta, horizon);
      }
      pWait = Math.min((aheadUnits + sizeUnits) / mu, MAX_WAIT_SECONDS);
    }
    pModel = model;
    pFill =
        FillCalibration.calibrate(
            model, t.calObserved[r] / SCALE, t.calPredicted[r] / SCALE, m.calibrationPriorFills());
    final double open = pFill * Math.min(pWait, horizon) + (1.0 - pFill) * horizon;
    pRisk = m.riskPerUnit(sigmaSec, open, q);
    final double message = rp.messageCostPoints() * lot / Math.max(1L, q);
    final double crossing =
        Double.isNaN(fallbackUnit)
            ? halfSpread + t.takerFee[r] / SCALE + pen[r]
            : fallbackUnit;
    final double adverse = rp.adverseFactor() * sigmaSec * StrictMath.sqrt(horizon);
    final double filledAdverse =
        rp.filledAdverseFactor() * sigmaSec * StrictMath.sqrt(Math.min(pWait, horizon));
    final double latency =
        rp.latencyRiskFactor() * sigmaSec * StrictMath.sqrt(t.latencyNanos[r] / SCALE);
    return offset
        + pen[r]
        + pFill * (-halfSpread + t.makerFee[r] / SCALE + filledAdverse)
        + (1.0 - pFill) * (adverse + crossing + 2.0 * message)
        + latency
        + message
        + pRisk
        + impactUnit(rp, m, t, q);
  }

  private void finish(
      final CostTable t, final int n, final boolean passive, final double aggUnit,
      final double passiveUnitMean, final ExecutionModel m, final RouterParams rp,
      final double sigmaSec, final double horizon, final long swept) {
    long allocated = 0;
    for (int r = 0; r < n; r++) {
      final VenueExclusion ex = t.exclusion[r];
      final boolean eligible =
          ex == VenueExclusion.None || ex == VenueExclusion.CapReached || ex == VenueExclusion.BelowMinimum;
      if (!eligible || ex == VenueExclusion.CapReached) {
        if (ex != VenueExclusion.CapReached) {
          t.penalty[r] = 0;
        }
        continue;
      }
      t.sweepQuantity[r] = taken[r];
      t.childLimit[r] = taken[r] > 0 ? last[r] : 0;
      t.walkCost[r] = taken[r] > 0 ? Math.round(walk[r] / taken[r] * SCALE) : 0;
      final long q = passive ? (pq[r] > 0 ? pq[r] : firstBlock[r]) : (taken[r] > 0 ? taken[r] : firstBlock[r]);
      t.evaluatedQuantity[r] = q;
      final double impact = impactUnit(rp, m, t, swept > 0 && !passive ? swept : q);
      t.impactCost[r] = Math.round(impact * SCALE);
      double passiveCost = 0.0;
      if (t.passiveAllowed[r] && horizon > 0.0) {
        passiveCost = passiveUnit(t, r, q, rp, m, sigmaSec, horizon);
        t.passiveCost[r] = Math.round(passiveCost * SCALE);
        t.fillProbability[r] = Math.round(pFill * SCALE);
        t.queueWaitNanos[r] = Math.round(pWait * SCALE);
        t.riskCost[r] = Math.round(pRisk * SCALE);
        modelP[r] = pModel;
      }
      double aggressiveCost = 0.0;
      if (taken[r] > 0) {
        aggressiveCost =
            (walk[r] + rp.messageCostPoints() * t.lotSize[r]) / taken[r] + impactUnit(rp, m, t, swept);
      } else if (!Double.isNaN(bestEntry[r])) {
        aggressiveCost = bestEntry[r] + impact;
      }
      t.aggressiveCost[r] = Math.round(aggressiveCost * SCALE);
      final long alloc = passive ? pq[r] : taken[r];
      t.allocated[r] = alloc;
      t.passive[r] = passive && alloc > 0;
      t.chosenCost[r] = t.passive[r] ? t.passiveCost[r] : t.aggressiveCost[r];
      if (alloc == 0 && ex == VenueExclusion.None && blocked[r]) {
        t.exclusion[r] = VenueExclusion.BelowMinimum;
      }
      allocated += alloc;
    }
    t.allocatedQuantity = allocated;
    if (allocated == 0) {
      t.expectedCost = 0;
    } else {
      t.expectedCost = Math.round((passive ? passiveUnitMean : aggUnit) * SCALE);
    }
  }

  /** Uncalibrated queue-model fill probability of row {@code r} in the last allocation. */
  double modelProbability(final int r) {
    return modelP[r];
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
    t.penalty[r] = 0;
    t.sweepQuantity[r] = 0;
    t.childLimit[r] = 0;
    t.walkCost[r] = 0;
    t.riskCost[r] = 0;
  }
}
