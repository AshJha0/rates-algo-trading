package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.VenueExclusion;
import io.ratesalgo.messages.sbe.VenueKind;
import org.junit.jupiter.api.Test;

/**
 * The queue fill model, the cost formulas of API_SPEC.md Section 15.1 and the allocation of
 * Section 15.2. Tolerances: relative 1e-12 with an absolute floor of 1e-15 for closed forms
 * ({@code REL}, {@code ABS}); 1e-10 for the uniformisation against the Poisson tail, as pinned in
 * the earlier library.
 */
class CostModelTest {

  private static final double REL = 1e-12;
  private static final double ABS = 1e-15;
  private static final long MM = RouterPresets.ONE_MILLION;

  private static void close(final double expected, final double actual, final double rel) {
    final double tol = Math.max(ABS, rel * Math.abs(expected));
    assertTrue(Math.abs(expected - actual) <= tol, "expected " + expected + " got " + actual);
  }

  @Test
  void queueModelWithoutCancelsIsThePoissonTail() {
    final QueueFillModel q = new QueueFillModel();
    final int ahead = 7;
    final int size = 3;
    final double mu = 1.3;
    final double t = 6.0;
    // P(N(mu t) >= ahead + size)
    final double lambda = mu * t;
    double term = Math.exp(-lambda);
    double cdf = 0.0;
    for (int k = 0; k < ahead + size; k++) {
      cdf += term;
      term = term * lambda / (k + 1);
    }
    close(1.0 - cdf, q.probability(ahead, size, mu, 0.0, t), 1e-10);
    assertEquals(0.0, q.probability(3, 1, 1.0, 0.0, 0.0));
    assertTrue(q.probability(0, 1, 1.0, 0.5, 2.0) > q.probability(10, 1, 1.0, 0.5, 2.0));
    assertTrue(q.probability(10, 1, 1.0, 0.5, 2.0) > q.probability(10, 1, 1.0, 0.0, 2.0));
  }

  @Test
  void queueModelRejectsBadInput() {
    final QueueFillModel q = new QueueFillModel();
    assertThrows(IllegalArgumentException.class, () -> q.probability(-1, 1, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> q.probability(0, 0, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> q.probability(4_999, 2, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> q.probability(0, 1, 0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> q.probability(0, 1, 1, -1, 1));
    assertThrows(IllegalArgumentException.class, () -> q.probability(0, 1, 1, 0, Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> q.probability(0, 1, 1e6, 0, 1e6));
  }

  /** A two-venue table with hand-set inputs. */
  static CostTable table(final long slice, final long horizonNanos) {
    final CostTable t = new CostTable();
    t.venueCount = 2;
    t.side = Side.Buy;
    t.sliceQuantity = slice;
    t.passiveHorizonNanos = horizonNanos;
    for (int r = 0; r < 2; r++) {
      t.venueId[r] = r + 1;
      t.kind[r] = VenueKind.Clob;
      t.bestBid[r] = 98_500_000_000L;
      t.bestAsk[r] = 98_500_000_000L + RouterPresets.TICK_64TH;
      t.tick[r] = RouterPresets.TICK_64TH;
      t.lotSize[r] = MM;
      t.minSize[r] = MM;
      t.sameSideDepth[r] = (r == 0 ? 12 : 6) * MM;
      t.oppositeDepth[r] = (r == 0 ? 60 : 30) * MM;
      t.tradeRate[r] = Math.round((r == 0 ? 2.0 : 1.0) * MM * 1e9);
      t.takerFee[r] = 500_000L;
      t.makerFee[r] = 200_000L;
      t.latencyNanos[r] = 250_000L;
      t.participationCap[r] = 100 * MM;
      t.passiveAllowed[r] = true;
      t.aggressiveAllowed[r] = true;
      t.exclusion[r] = VenueExclusion.None;
    }
    return t;
  }

  static VenueParams[] venues() {
    return new VenueParams[] {RouterPresets.clob(1), RouterPresets.clob(2)};
  }

  @Test
  void aggressiveCostIsHalfSpreadPlusImpactFeeAndLatency() {
    final RouterParams rp = RouterPresets.global();
    final VenueParams vp = RouterPresets.clob(1);
    final CostTable t = table(10 * MM, 0);
    final Allocator a = new Allocator();
    final double chosen = a.evaluate(t, 0, 10 * MM, rp, vp);
    final double hs = RouterPresets.TICK_64TH / 2e9;
    final double impact = 0.6 * 0.5 * Math.sqrt(10.0 * MM / vp.adv());
    final double latency = 1.0 * 0.5 / Math.sqrt(23_400.0) * Math.sqrt(250e-6);
    close(hs + impact + 0.0005 + latency, chosen, REL);
  }

  /**
   * DR-R2R-04: two venues with identical parameters and spreads, venue 2's book one tick lower.
   * A buyer pays one tick less on venue 2, so the whole slice goes there; a seller gets one tick
   * more on venue 1. Before the offset term every cost was equal and the tie went to row 0.
   */
  @Test
  void aVenueQuotingAtABetterPriceWinsTheSlice() {
    final RouterParams rp = RouterPresets.global();
    final CostTable buy = table(4 * MM, 0);
    buy.sameSideDepth[1] = buy.sameSideDepth[0];
    buy.oppositeDepth[1] = buy.oppositeDepth[0];
    buy.tradeRate[1] = buy.tradeRate[0];
    buy.bestBid[1] -= RouterPresets.TICK_64TH;
    buy.bestAsk[1] -= RouterPresets.TICK_64TH;
    final VenueParams[] same = {RouterPresets.clob(1), RouterPresets.clob(1)};
    final Allocator a = new Allocator();
    close(RouterPresets.TICK_64TH / 1e9,
        a.evaluate(buy, 0, MM, rp, same[0]) - a.evaluate(buy, 1, MM, rp, same[1]), REL);
    a.allocate(buy, rp, same);
    assertEquals(0L, buy.allocated[0], buy.format());
    assertEquals(4 * MM, buy.allocated[1], buy.format());
    final CostTable sell = new CostTable();
    sell.copyFrom(buy);
    sell.side = Side.Sell;
    a.allocate(sell, rp, same);
    assertEquals(4 * MM, sell.allocated[0], sell.format());
    assertEquals(0L, sell.allocated[1], sell.format());
    assertEquals(0.0, Allocator.venueOffset(table(MM, 0), 0));
  }

  @Test
  void passiveIsChosenWhenTheQueueIsShortAndTheHorizonLong() {
    final RouterParams rp = RouterPresets.global();
    final CostTable t = table(2 * MM, 30_000_000_000L);
    t.sameSideDepth[1] = 0;
    t.tradeRate[1] = Math.round(20.0 * MM * 1e9);
    final Allocator a = new Allocator();
    a.allocate(t, rp, venues());
    assertTrue(t.passive[1], t.format());
    assertEquals(t.sliceQuantity, t.allocatedQuantity);
  }

  @Test
  void allocationSplitsAcrossVenuesAndRespectsCapsAndMinimums() {
    final RouterParams rp = RouterPresets.global();
    final CostTable t = table(40 * MM, 0);
    t.participationCap[1] = 5 * MM;
    final Allocator a = new Allocator();
    a.allocate(t, rp, venues());
    assertEquals(40 * MM, t.allocatedQuantity, t.format());
    assertTrue(t.allocated[0] > 0 && t.allocated[1] > 0, t.format());
    assertTrue(t.allocated[1] <= 5 * MM);
    assertEquals(0, t.allocated[0] % MM);

    final CostTable u = table(3 * MM, 0);
    u.minSize[1] = 5 * MM;
    a.allocate(u, rp, venues());
    assertEquals(VenueExclusion.BelowMinimum, u.exclusion[1]);
    assertEquals(3 * MM, u.allocated[0]);

    final CostTable w = table(3 * MM, 0);
    w.participationCap[0] = 0;
    w.participationCap[1] = 0;
    a.allocate(w, rp, venues());
    assertEquals(0, w.allocatedQuantity);
    assertEquals(VenueExclusion.CapReached, w.exclusion[0]);
    assertEquals(0, w.expectedCost);
  }

  @Test
  void greedyMatchesBruteForceForConvexCosts() {
    final RouterParams rp = RouterPresets.global();
    final Allocator a = new Allocator();
    for (long lots = 1; lots <= 20; lots++) {
      final CostTable t = table(lots * MM, 0);
      t.oppositeDepth[1] = 10 * MM;
      a.allocate(t, rp, venues());
      final double greedy = total(a, t, rp, t.allocated[0], t.allocated[1]);
      double best = Double.POSITIVE_INFINITY;
      for (long q0 = 0; q0 <= lots; q0++) {
        best = Math.min(best, total(a, t, rp, q0 * MM, (lots - q0) * MM));
      }
      close(best, greedy, 1e-9);
    }
  }

  private static double total(
      final Allocator a, final CostTable t, final RouterParams rp, final long q0, final long q1) {
    double sum = 0.0;
    if (q0 > 0) {
      sum += q0 * a.evaluate(t, 0, q0, rp, RouterPresets.clob(1));
    }
    if (q1 > 0) {
      sum += q1 * a.evaluate(t, 1, q1, rp, RouterPresets.clob(2));
    }
    return sum;
  }

  @Test
  void excludedRowsGetNoCosts() {
    final CostTable t = table(4 * MM, 0);
    t.exclusion[0] = VenueExclusion.NoQuote;
    new Allocator().allocate(t, RouterPresets.global(), venues());
    assertEquals(0, t.chosenCost[0]);
    assertEquals(4 * MM, t.allocated[1]);
    assertFalse(t.passive[1]);
    final CostTable copy = new CostTable();
    copy.copyFrom(t);
    assertTrue(copy.contentEquals(t));
    copy.chosenCost[1]++;
    assertFalse(copy.contentEquals(t));
    copy.copyFrom(t);
    copy.timestampNanos++;
    assertFalse(copy.contentEquals(t));
    assertTrue(t.format().contains("NoQuote"));
  }

  @Test
  void parametersAreRangeChecked() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new VenueParams(1, VenueKind.Clob, 1, MM, MM / 2, 0, 0, 0, 0, 1, 0.5, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VenueParams(0, VenueKind.Clob, 1, MM, MM, 0, 0, 0, 0, 1, 0.5, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> RouterPresets.global().withHorizons(-1, 1_000_000_000L));
    final RouterParams p = RouterPresets.global().withHorizons(1_000L, 2_000L);
    assertEquals(2_000L, p.passiveHorizonMaxNanos());
    assertEquals(VenueKind.Futures, RouterPresets.futures(5).kind());
  }
}
