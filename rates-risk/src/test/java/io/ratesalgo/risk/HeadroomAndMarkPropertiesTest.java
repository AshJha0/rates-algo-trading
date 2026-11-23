package io.ratesalgo.risk;

import static io.ratesalgo.risk.RiskFixture.ALL;
import static io.ratesalgo.risk.RiskFixture.M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.messages.sbe.Side;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Headroom is the exact edge of the single-order check for every limit kind alone and in any
 * combination, the unbounded range included; and an instrument that was never filled has no
 * position, so its first mark leaves daily P&amp;L where it was.
 */
class HeadroomAndMarkPropertiesTest {

  private static final Side[] SIDES = {Side.Buy, Side.Sell};

  // bit i of the mask switches on limit kind i
  private static final int POSITION = 1;
  private static final int ORDER_SIZE = 1 << 1;
  private static final int BUCKET = 1 << 2;
  private static final int NET = 1 << 3;
  private static final int GROSS = 1 << 4;
  private static final int BASIS = 1 << 5;
  private static final int SPREAD = 1 << 6;

  private static RiskLimits limits(final int kinds, final Random rnd) {
    final RiskLimits l = new RiskLimits().dailyLoss(1e18);
    if ((kinds & POSITION) != 0) {
      l.defaultMaxPosition(rnd.nextLong(1, 600) * M + rnd.nextLong(M));
    }
    if ((kinds & ORDER_SIZE) != 0) {
      l.maxOrderSize(rnd.nextLong(1, 400) * M + rnd.nextLong(M));
    }
    if ((kinds & BUCKET) != 0) {
      l.bucketPv01(rnd.nextDouble(1_000.0, 200_000.0));
    }
    if ((kinds & NET) != 0) {
      l.netPv01(rnd.nextDouble(1_000.0, 300_000.0));
    }
    if ((kinds & GROSS) != 0) {
      l.grossPv01(rnd.nextDouble(1_000.0, 500_000.0));
    }
    if ((kinds & BASIS) != 0) {
      l.basisPv01(rnd.nextDouble(1_000.0, 150_000.0));
    }
    if ((kinds & SPREAD) != 0) {
      l.swapSpreadPv01(rnd.nextDouble(1_000.0, 150_000.0));
    }
    return l;
  }

  /**
   * For any set of limits, any book and any reservations: an order of exactly the headroom passes
   * the engine's own check (or the headroom is zero), and one minor unit more is refused (or the
   * headroom is Long.MAX_VALUE, where no more can be asked).
   */
  @Property(tries = 400, seed = "20261123")
  void headroomIsTheExactEdgeForEveryLimitKindAloneAndCombined(
      @ForAll @IntRange(min = 0, max = 127) final int kinds, @ForAll final long seed) {
    final Random rnd = new Random(seed);
    final RiskFixture f = new RiskFixture(limits(kinds, rnd));
    final int fills = rnd.nextInt(5);
    for (int n = 0; n < fills; n++) {
      final int id = ALL[rnd.nextInt(ALL.length)];
      f.risk.onFill(id, (rnd.nextInt(401) - 200) * M + rnd.nextInt(1000), f.fairMid(id));
    }
    final int reservations = rnd.nextInt(4);
    for (int n = 0; n < reservations; n++) {
      f.risk.reserve(ALL[rnd.nextInt(ALL.length)], rnd.nextInt(80) * M, rnd.nextInt(80) * M);
    }
    long seq = 0;
    for (final int id : ALL) {
      for (final Side side : SIDES) {
        final long h = f.risk.headroom(id, side);
        final String where = "kinds " + kinds + " " + id + " " + side + " h " + h;
        assertTrue(h >= 0, where);
        if (h > 0) {
          assertEquals(RiskEngine.OK, f.risk.checkOrder(id, side, h, f.fairMid(id), ++seq), "at the edge, " + where);
        }
        if (h < Long.MAX_VALUE) {
          assertNotEquals(RiskEngine.OK, f.risk.checkOrder(id, side, h + 1, f.fairMid(id), ++seq), "past the edge, " + where);
        }
      }
    }
  }

  /**
   * Every writer of the position and mark arrays (fills, marks, curve moves, reservations, start
   * of day) in random order: an instrument never filled has a zero position throughout, and its
   * first mark changes daily P&amp;L by exactly nothing. This is the invariant that lets
   * {@code onFairValue} skip the mark value for an unmarked instrument.
   */
  @Property(tries = 300, seed = "7")
  void anInstrumentNeverFilledHasNoPositionAndItsFirstMarkAddsNothing(@ForAll final long seed) {
    final Random rnd = new Random(seed);
    final RiskFixture f = new RiskFixture();
    final Set<Integer> filled = new HashSet<>();
    final Set<Integer> marked = new HashSet<>();
    int firstMarks = 0;
    for (int step = 0; step < 60; step++) {
      final int id = ALL[rnd.nextInt(ALL.length)];
      final CurrencyCode ccy = f.directory.byId(id).currency();
      switch (rnd.nextInt(6)) {
        case 0 -> {
          f.risk.onFill(id, (rnd.nextInt(201) - 100) * M, f.fairMid(id));
          filled.add(id);
          marked.add(id);
        }
        case 1 -> {
          final boolean first = !marked.contains(id);
          final double before = f.risk.dailyPnl(ccy);
          f.risk.onFairValue(id, f.fairMid(id) + Decimal9.round(rnd.nextDouble(-2.0, 2.0)));
          if (first) {
            assertEquals(before, f.risk.dailyPnl(ccy), "first mark of " + id + " moved P&L");
            firstMarks++;
          }
          marked.add(id);
        }
        case 2 -> f.risk.onCurveMove(ccy);
        case 3 -> f.risk.reserve(id, rnd.nextInt(50) * M, rnd.nextInt(50) * M);
        case 4 -> f.risk.release(id, f.risk.pendingBuy(id), f.risk.pendingSell(id));
        default -> f.risk.startOfDay();
      }
      for (final int other : ALL) {
        if (!filled.contains(other)) {
          assertEquals(0L, f.risk.position(other), "never filled, yet a position on " + other);
        }
      }
    }
    assertTrue(firstMarks <= ALL.length);
  }
}
