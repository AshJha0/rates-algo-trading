package io.ratesalgo.strategy;

/**
 * Audit item 1 (DR-V1H-01): how {@link DeskQuoter} executes the hedge the {@link HedgeBudget}
 * asks for. Without it the strategy sends one market order for the whole excess.
 *
 * <pre>
 *   child   = min(remaining, max(minChildLots, floor(depthFraction * touch lots on the side we would take)))
 *   passive: a limit order for the child at our own touch (buy at the bid, sell at the ask)
 *   after passiveWait unfilled: cancel and send the child as a market order (escalation)
 *   |net DV01| &gt; urgentDv01, or our side of the book empty: the child goes as a market order at once
 *   after each child fills, the budget is asked again, so client flow that arrived meanwhile nets first
 * </pre>
 *
 * Sizing the child from the opposite touch means an escalated child normally fills at the touch
 * without walking the book. {@code passiveWaitNanos} of zero sends every child as a market order
 * (slicing without passive placement).
 */
public record HedgeSlicing(double depthFraction, long minChildLots, long passiveWaitNanos, double urgentDv01) {

  public HedgeSlicing {
    if (!(depthFraction > 0) || minChildLots < 1 || passiveWaitNanos < 0 || !(urgentDv01 > 0)) {
      throw new IllegalArgumentException(
          "hedge slicing needs depth fraction > 0, min child >= 1 lot, wait >= 0 and urgent > 0");
    }
  }

  /** Lots of the next child for {@code remainingLots} still to hedge and {@code touchLots} displayed. */
  public long childLots(final long remainingLots, final long touchLots) {
    final long byDepth = (long) Math.floor(depthFraction * touchLots);
    return Math.min(remainingLots, Math.max(minChildLots, byDepth));
  }
}
