package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.RouteReason;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.VenueExclusion;
import io.ratesalgo.messages.sbe.VenueKind;
import java.util.Arrays;
import java.util.Locale;

/**
 * The cost table of one routing decision: the header, and per venue the inputs read at decision
 * time, the costs the model computed and the quantity allocated. It is exactly the content of one
 * RouteDecision (template 80) and its RouteDecisionVenue rows (template 81), so a table decoded
 * from the journal compares equal, field by field, with the table the router built.
 *
 * <p>Costs are per unit of quantity in price points, stored as int64 mantissas with exponent -9.
 * The router reuses one instance; fields are public arrays for allocation-free access on the
 * decision path. Not thread-safe.
 */
public final class CostTable {

  /** Most venues one decision can consider. */
  public static final int MAX_VENUES = 8;

  /** Most opposite-side levels per venue the ladder model reads (LoopBook keeps 16). */
  public static final int MAX_LEVELS = 16;

  // header
  long decisionId;
  long parentId;
  int instrumentId;
  Side side = Side.Buy;
  RouteReason reason = RouteReason.Slice;
  int configVersion;
  int venueCount;
  int sliceIndex;
  long parentQuantity;
  long sliceQuantity;
  long allocatedQuantity;
  long urgency;
  long passiveHorizonNanos;
  long sliceIntervalNanos;
  long arrivalMid;
  long expectedCost;
  long timestampNanos;

  // inputs per row
  final int[] venueId = new int[MAX_VENUES];
  final VenueKind[] kind = new VenueKind[MAX_VENUES];
  final long[] bestBid = new long[MAX_VENUES];
  final long[] bestAsk = new long[MAX_VENUES];
  final long[] tick = new long[MAX_VENUES];
  final long[] lotSize = new long[MAX_VENUES];
  final long[] minSize = new long[MAX_VENUES];
  final long[] sameSideDepth = new long[MAX_VENUES];
  final long[] oppositeDepth = new long[MAX_VENUES];
  final long[] tradeRate = new long[MAX_VENUES];
  final long[] takerFee = new long[MAX_VENUES];
  final long[] makerFee = new long[MAX_VENUES];
  final long[] latencyNanos = new long[MAX_VENUES];
  final long[] participationCap = new long[MAX_VENUES];
  final boolean[] passiveAllowed = new boolean[MAX_VENUES];
  final boolean[] aggressiveAllowed = new boolean[MAX_VENUES];
  final VenueExclusion[] exclusion = new VenueExclusion[MAX_VENUES];

  // ladder model (RouteDecisionModel and RouteDecisionLadder frames); off for the touch model
  boolean ladder;
  int modelVersion;
  final int[] levelCount = new int[MAX_VENUES];
  final long[] levelPrice = new long[MAX_VENUES * MAX_LEVELS];
  final long[] levelDisplayed = new long[MAX_VENUES * MAX_LEVELS];
  final long[] levelOwn = new long[MAX_VENUES * MAX_LEVELS];
  final long[] limitPrice = new long[MAX_VENUES];
  final long[] ownOpposite = new long[MAX_VENUES];
  final long[] rejectRate = new long[MAX_VENUES];
  final long[] cancelRejectRate = new long[MAX_VENUES];
  final long[] fillRatio = new long[MAX_VENUES];
  final long[] markout1s = new long[MAX_VENUES];
  final long[] markout5s = new long[MAX_VENUES];
  final long[] markout30s = new long[MAX_VENUES];
  final long[] ackLatencyNanos = new long[MAX_VENUES];
  final long[] cancelRate = new long[MAX_VENUES];
  final long[] calObserved = new long[MAX_VENUES];
  final long[] calPredicted = new long[MAX_VENUES];
  final int[] queueBucket = new int[MAX_VENUES];
  final long[] penalty = new long[MAX_VENUES];
  final long[] sweepQuantity = new long[MAX_VENUES];
  final long[] childLimit = new long[MAX_VENUES];
  final long[] walkCost = new long[MAX_VENUES];
  final long[] riskCost = new long[MAX_VENUES];

  // outputs per row
  final long[] evaluatedQuantity = new long[MAX_VENUES];
  final long[] allocated = new long[MAX_VENUES];
  final boolean[] passive = new boolean[MAX_VENUES];
  final long[] fillProbability = new long[MAX_VENUES];
  final long[] queueWaitNanos = new long[MAX_VENUES];
  final long[] impactCost = new long[MAX_VENUES];
  final long[] passiveCost = new long[MAX_VENUES];
  final long[] aggressiveCost = new long[MAX_VENUES];
  final long[] chosenCost = new long[MAX_VENUES];

  /** An empty table. */
  public CostTable() {
    Arrays.fill(kind, VenueKind.Clob);
    Arrays.fill(exclusion, VenueExclusion.None);
  }

  /** Decision identifier. */
  public long decisionId() {
    return decisionId;
  }

  /** Parent order identifier. */
  public long parentId() {
    return parentId;
  }

  /** Why the decision was taken. */
  public RouteReason reason() {
    return reason;
  }

  /** Number of venue rows. */
  public int venueCount() {
    return venueCount;
  }

  /** Quantity the decision had to place. */
  public long sliceQuantity() {
    return sliceQuantity;
  }

  /** Quantity actually allocated across venues. */
  public long allocatedQuantity() {
    return allocatedQuantity;
  }

  /** Venue of a row. */
  public int venueId(final int row) {
    return venueId[row];
  }

  /** Quantity allocated to a row. */
  public long allocated(final int row) {
    return allocated[row];
  }

  /** Whether the row's child is passive. */
  public boolean passive(final int row) {
    return passive[row];
  }

  /** Why a row was excluded, or None. */
  public VenueExclusion exclusion(final int row) {
    return exclusion[row];
  }

  /** Chosen expected cost per unit of a row, price points mantissa. */
  public long chosenCost(final int row) {
    return chosenCost[row];
  }

  /** Quantity-weighted expected cost per unit of the decision, price points mantissa. */
  public long expectedCost() {
    return expectedCost;
  }

  /** True if the ladder model took the decision. */
  public boolean isLadder() {
    return ladder;
  }

  /** Aggressive cost per unit of a row, price points mantissa. */
  public long aggressiveCost(final int row) {
    return aggressiveCost[row];
  }

  /** Passive cost per unit of a row, price points mantissa. */
  public long passiveCost(final int row) {
    return passiveCost[row];
  }

  /** Post-trade impact per unit of a row, price points mantissa. */
  public long impactCost(final int row) {
    return impactCost[row];
  }

  /** Fill probability of a row's passive child, mantissa. */
  public long fillProbability(final int row) {
    return fillProbability[row];
  }

  /** Ladder levels journaled for a row. */
  public int levelCount(final int row) {
    return levelCount[row];
  }

  /** Price of a row's ladder level. */
  public long levelPrice(final int row, final int level) {
    return levelPrice[row * MAX_LEVELS + level];
  }

  /** Most aggressive price allowed on a row (ladder model), 0 if none. */
  public long limitPrice(final int row) {
    return limitPrice[row];
  }

  /** Limit of a row's aggressive child under the ladder model: the last level it needs. */
  public long childLimit(final int row) {
    return childLimit[row];
  }

  /** Quantity the consolidated sweep takes on a row. */
  public long sweepQuantity(final int row) {
    return sweepQuantity[row];
  }

  /** Mean all-in cost per unit of a row's sweep levels, price points mantissa. */
  public long walkCost(final int row) {
    return walkCost[row];
  }

  /** Scorecard penalty of a row, price points per unit mantissa. */
  public long penalty(final int row) {
    return penalty[row];
  }

  /** Risk charge per unit of a row's passive leg, price points mantissa. */
  public long riskCost(final int row) {
    return riskCost[row];
  }

  /** Learned cancellation rate of a row, per unit per second mantissa. */
  public long cancelRate(final int row) {
    return cancelRate[row];
  }

  /** Clears the outputs of every row, keeping the inputs; used to re-evaluate a decoded table. */
  public void clearOutputs() {
    allocatedQuantity = 0;
    expectedCost = 0;
    for (int r = 0; r < MAX_VENUES; r++) {
      evaluatedQuantity[r] = 0;
      allocated[r] = 0;
      passive[r] = false;
      fillProbability[r] = 0;
      queueWaitNanos[r] = 0;
      impactCost[r] = 0;
      passiveCost[r] = 0;
      aggressiveCost[r] = 0;
      chosenCost[r] = 0;
      penalty[r] = 0;
      sweepQuantity[r] = 0;
      childLimit[r] = 0;
      walkCost[r] = 0;
      riskCost[r] = 0;
      if (exclusion[r] == VenueExclusion.BelowMinimum
          || exclusion[r] == VenueExclusion.CapReached) {
        exclusion[r] = VenueExclusion.None;
      }
    }
  }

  /** Field-by-field equality of the header and the first {@code venueCount} rows. */
  public boolean contentEquals(final CostTable o) {
    if (decisionId != o.decisionId
        || parentId != o.parentId
        || instrumentId != o.instrumentId
        || side != o.side
        || reason != o.reason
        || configVersion != o.configVersion
        || venueCount != o.venueCount
        || sliceIndex != o.sliceIndex
        || parentQuantity != o.parentQuantity
        || sliceQuantity != o.sliceQuantity
        || allocatedQuantity != o.allocatedQuantity
        || urgency != o.urgency
        || passiveHorizonNanos != o.passiveHorizonNanos
        || sliceIntervalNanos != o.sliceIntervalNanos
        || arrivalMid != o.arrivalMid
        || expectedCost != o.expectedCost
        || timestampNanos != o.timestampNanos
        || ladder != o.ladder
        || modelVersion != o.modelVersion) {
      return false;
    }
    for (int r = 0; r < venueCount; r++) {
      if (!rowEquals(o, r)) {
        return false;
      }
    }
    return true;
  }

  private boolean rowEquals(final CostTable o, final int r) {
    return venueId[r] == o.venueId[r]
        && kind[r] == o.kind[r]
        && bestBid[r] == o.bestBid[r]
        && bestAsk[r] == o.bestAsk[r]
        && tick[r] == o.tick[r]
        && lotSize[r] == o.lotSize[r]
        && minSize[r] == o.minSize[r]
        && sameSideDepth[r] == o.sameSideDepth[r]
        && oppositeDepth[r] == o.oppositeDepth[r]
        && tradeRate[r] == o.tradeRate[r]
        && takerFee[r] == o.takerFee[r]
        && makerFee[r] == o.makerFee[r]
        && latencyNanos[r] == o.latencyNanos[r]
        && participationCap[r] == o.participationCap[r]
        && passiveAllowed[r] == o.passiveAllowed[r]
        && aggressiveAllowed[r] == o.aggressiveAllowed[r]
        && exclusion[r] == o.exclusion[r]
        && evaluatedQuantity[r] == o.evaluatedQuantity[r]
        && allocated[r] == o.allocated[r]
        && passive[r] == o.passive[r]
        && fillProbability[r] == o.fillProbability[r]
        && queueWaitNanos[r] == o.queueWaitNanos[r]
        && impactCost[r] == o.impactCost[r]
        && passiveCost[r] == o.passiveCost[r]
        && aggressiveCost[r] == o.aggressiveCost[r]
        && chosenCost[r] == o.chosenCost[r]
        && (!ladder || ladderRowEquals(o, r));
  }

  private boolean ladderRowEquals(final CostTable o, final int r) {
    if (levelCount[r] != o.levelCount[r]) {
      return false;
    }
    for (int k = 0; k < levelCount[r]; k++) {
      final int i = r * MAX_LEVELS + k;
      if (levelPrice[i] != o.levelPrice[i]
          || levelDisplayed[i] != o.levelDisplayed[i]
          || levelOwn[i] != o.levelOwn[i]) {
        return false;
      }
    }
    return limitPrice[r] == o.limitPrice[r]
        && ownOpposite[r] == o.ownOpposite[r]
        && rejectRate[r] == o.rejectRate[r]
        && cancelRejectRate[r] == o.cancelRejectRate[r]
        && fillRatio[r] == o.fillRatio[r]
        && markout1s[r] == o.markout1s[r]
        && markout5s[r] == o.markout5s[r]
        && markout30s[r] == o.markout30s[r]
        && ackLatencyNanos[r] == o.ackLatencyNanos[r]
        && cancelRate[r] == o.cancelRate[r]
        && calObserved[r] == o.calObserved[r]
        && calPredicted[r] == o.calPredicted[r]
        && queueBucket[r] == o.queueBucket[r]
        && penalty[r] == o.penalty[r]
        && sweepQuantity[r] == o.sweepQuantity[r]
        && childLimit[r] == o.childLimit[r]
        && walkCost[r] == o.walkCost[r]
        && riskCost[r] == o.riskCost[r];
  }

  /** Copies everything from another table. */
  public void copyFrom(final CostTable o) {
    decisionId = o.decisionId;
    parentId = o.parentId;
    instrumentId = o.instrumentId;
    side = o.side;
    reason = o.reason;
    configVersion = o.configVersion;
    venueCount = o.venueCount;
    sliceIndex = o.sliceIndex;
    parentQuantity = o.parentQuantity;
    sliceQuantity = o.sliceQuantity;
    allocatedQuantity = o.allocatedQuantity;
    urgency = o.urgency;
    passiveHorizonNanos = o.passiveHorizonNanos;
    sliceIntervalNanos = o.sliceIntervalNanos;
    arrivalMid = o.arrivalMid;
    expectedCost = o.expectedCost;
    timestampNanos = o.timestampNanos;
    System.arraycopy(o.venueId, 0, venueId, 0, MAX_VENUES);
    System.arraycopy(o.kind, 0, kind, 0, MAX_VENUES);
    System.arraycopy(o.bestBid, 0, bestBid, 0, MAX_VENUES);
    System.arraycopy(o.bestAsk, 0, bestAsk, 0, MAX_VENUES);
    System.arraycopy(o.tick, 0, tick, 0, MAX_VENUES);
    System.arraycopy(o.lotSize, 0, lotSize, 0, MAX_VENUES);
    System.arraycopy(o.minSize, 0, minSize, 0, MAX_VENUES);
    System.arraycopy(o.sameSideDepth, 0, sameSideDepth, 0, MAX_VENUES);
    System.arraycopy(o.oppositeDepth, 0, oppositeDepth, 0, MAX_VENUES);
    System.arraycopy(o.tradeRate, 0, tradeRate, 0, MAX_VENUES);
    System.arraycopy(o.takerFee, 0, takerFee, 0, MAX_VENUES);
    System.arraycopy(o.makerFee, 0, makerFee, 0, MAX_VENUES);
    System.arraycopy(o.latencyNanos, 0, latencyNanos, 0, MAX_VENUES);
    System.arraycopy(o.participationCap, 0, participationCap, 0, MAX_VENUES);
    System.arraycopy(o.passiveAllowed, 0, passiveAllowed, 0, MAX_VENUES);
    System.arraycopy(o.aggressiveAllowed, 0, aggressiveAllowed, 0, MAX_VENUES);
    System.arraycopy(o.exclusion, 0, exclusion, 0, MAX_VENUES);
    System.arraycopy(o.evaluatedQuantity, 0, evaluatedQuantity, 0, MAX_VENUES);
    System.arraycopy(o.allocated, 0, allocated, 0, MAX_VENUES);
    System.arraycopy(o.passive, 0, passive, 0, MAX_VENUES);
    System.arraycopy(o.fillProbability, 0, fillProbability, 0, MAX_VENUES);
    System.arraycopy(o.queueWaitNanos, 0, queueWaitNanos, 0, MAX_VENUES);
    System.arraycopy(o.impactCost, 0, impactCost, 0, MAX_VENUES);
    System.arraycopy(o.passiveCost, 0, passiveCost, 0, MAX_VENUES);
    System.arraycopy(o.aggressiveCost, 0, aggressiveCost, 0, MAX_VENUES);
    System.arraycopy(o.chosenCost, 0, chosenCost, 0, MAX_VENUES);
    ladder = o.ladder;
    modelVersion = o.modelVersion;
    System.arraycopy(o.levelCount, 0, levelCount, 0, MAX_VENUES);
    System.arraycopy(o.levelPrice, 0, levelPrice, 0, levelPrice.length);
    System.arraycopy(o.levelDisplayed, 0, levelDisplayed, 0, levelDisplayed.length);
    System.arraycopy(o.levelOwn, 0, levelOwn, 0, levelOwn.length);
    System.arraycopy(o.limitPrice, 0, limitPrice, 0, MAX_VENUES);
    System.arraycopy(o.ownOpposite, 0, ownOpposite, 0, MAX_VENUES);
    System.arraycopy(o.rejectRate, 0, rejectRate, 0, MAX_VENUES);
    System.arraycopy(o.cancelRejectRate, 0, cancelRejectRate, 0, MAX_VENUES);
    System.arraycopy(o.fillRatio, 0, fillRatio, 0, MAX_VENUES);
    System.arraycopy(o.markout1s, 0, markout1s, 0, MAX_VENUES);
    System.arraycopy(o.markout5s, 0, markout5s, 0, MAX_VENUES);
    System.arraycopy(o.markout30s, 0, markout30s, 0, MAX_VENUES);
    System.arraycopy(o.ackLatencyNanos, 0, ackLatencyNanos, 0, MAX_VENUES);
    System.arraycopy(o.cancelRate, 0, cancelRate, 0, MAX_VENUES);
    System.arraycopy(o.calObserved, 0, calObserved, 0, MAX_VENUES);
    System.arraycopy(o.calPredicted, 0, calPredicted, 0, MAX_VENUES);
    System.arraycopy(o.queueBucket, 0, queueBucket, 0, MAX_VENUES);
    System.arraycopy(o.penalty, 0, penalty, 0, MAX_VENUES);
    System.arraycopy(o.sweepQuantity, 0, sweepQuantity, 0, MAX_VENUES);
    System.arraycopy(o.childLimit, 0, childLimit, 0, MAX_VENUES);
    System.arraycopy(o.walkCost, 0, walkCost, 0, MAX_VENUES);
    System.arraycopy(o.riskCost, 0, riskCost, 0, MAX_VENUES);
  }

  /**
   * The decision as text: a header line and one line per venue. Prices in price points, costs in
   * price points per unit, quantities in millions of face (minor units / 1e8). Allocates; for
   * reports, recipes and tests, not the decision path.
   */
  public String format() {
    final StringBuilder sb = new StringBuilder(1_024);
    sb.append(
        String.format(
            Locale.ROOT,
            "decision %d parent %d %s %s slice %d qty %s alloc %s horizon %.1fs expected %s%n",
            decisionId,
            parentId,
            reason,
            side,
            sliceIndex,
            mm(sliceQuantity),
            mm(allocatedQuantity),
            passiveHorizonNanos / 1e9,
            pts(expectedCost)));
    sb.append(
        String.format(
            Locale.ROOT,
            "%-5s %-7s %-10s %-10s %6s %6s %6s %-7s %-9s %-9s %-9s %-9s %6s %-4s %s%n",
            "venue",
            "kind",
            "bid",
            "ask",
            "same",
            "opp",
            "cap",
            "P(fill)",
            "impact",
            "passive",
            "aggress",
            "chosen",
            "alloc",
            "mode",
            "excluded"));
    for (int r = 0; r < venueCount; r++) {
      sb.append(
          String.format(
              Locale.ROOT,
              "%-5d %-7s %-10s %-10s %6s %6s %6s %-7s %-9s %-9s %-9s %-9s %6s %-4s %s%n",
              venueId[r],
              kind[r],
              price(bestBid[r]),
              price(bestAsk[r]),
              mm(sameSideDepth[r]),
              mm(oppositeDepth[r]),
              mm(participationCap[r]),
              String.format(Locale.ROOT, "%.4f", fillProbability[r] / 1e9),
              pts(impactCost[r]),
              pts(passiveCost[r]),
              pts(aggressiveCost[r]),
              pts(chosenCost[r]),
              mm(allocated[r]),
              allocated[r] == 0 ? "-" : passive[r] ? "pass" : "aggr",
              exclusion[r]));
    }
    if (ladder) {
      for (int r = 0; r < venueCount; r++) {
        sb.append(
            String.format(
                Locale.ROOT,
                "  ladder %d: limit %s penalty %s sweep %s to %s walk %s risk %s theta %.4f levels",
                venueId[r],
                price(limitPrice[r]),
                pts(penalty[r]),
                mm(sweepQuantity[r]),
                price(childLimit[r]),
                pts(walkCost[r]),
                pts(riskCost[r]),
                cancelRate[r] / 1e9));
        for (int k = 0; k < levelCount[r]; k++) {
          final int i = r * MAX_LEVELS + k;
          sb.append(' ')
              .append(price(levelPrice[i]))
              .append('x')
              .append(mm(levelDisplayed[i] - levelOwn[i]));
        }
        sb.append(System.lineSeparator());
      }
    }
    return sb.toString();
  }

  private static String mm(final long quantity) {
    return String.format(Locale.ROOT, "%.1f", quantity / (double) RouterPresets.ONE_MILLION);
  }

  private static String price(final long mantissa) {
    return String.format(Locale.ROOT, "%.6f", mantissa / 1e9);
  }

  private static String pts(final long mantissa) {
    return String.format(Locale.ROOT, "%.6f", mantissa / 1e9);
  }
}
