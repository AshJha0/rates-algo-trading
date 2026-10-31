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
        || timestampNanos != o.timestampNanos) {
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
        && chosenCost[r] == o.chosenCost[r];
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
