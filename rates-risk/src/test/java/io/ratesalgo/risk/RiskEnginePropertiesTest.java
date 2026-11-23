package io.ratesalgo.risk;

import static io.ratesalgo.risk.RiskFixture.ALL;
import static io.ratesalgo.risk.RiskFixture.M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.messages.sbe.Side;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

/**
 * Invariants of the risk engine that hold for any book: reservations are conserved and their
 * effect does not depend on the path that built them, a refused check changes nothing, loosening
 * every limit never turns an accepted check into a refusal, headroom is the exact edge of the
 * envelope, and daily P&amp;L equals the mark-to-market of the fills recomputed in BigDecimal.
 */
class RiskEnginePropertiesTest {

  private static final Side[] SIDES = {Side.Buy, Side.Sell};

  /** Limits that bind on books of a few hundred million; {@code scale} loosens all of them. */
  private static RiskLimits limits(final double scale) {
    return new RiskLimits()
        .defaultMaxPosition((long) (400 * M * scale))
        .bucketPv01(90_000.0 * scale)
        .netPv01(150_000.0 * scale)
        .grossPv01(320_000.0 * scale)
        .basisPv01(60_000.0 * scale)
        .swapSpreadPv01(70_000.0 * scale)
        .maxOrderSize((long) (300 * M * scale))
        .dailyLoss(1e15);
  }

  /** Fills that put the book somewhere random, applied identically to every fixture given. */
  private static void randomBook(final Random rnd, final int fills, final RiskFixture... fixtures) {
    for (int n = 0; n < fills; n++) {
      final int id = ALL[rnd.nextInt(ALL.length)];
      final long qty = (rnd.nextInt(301) - 150) * M;
      for (final RiskFixture f : fixtures) {
        f.risk.onFill(id, qty, f.fairMid(id));
      }
    }
  }

  @Property(tries = 150, seed = "20261123")
  void reservationsAreConservedAndTheirEffectDoesNotDependOnThePath(@ForAll final long seed) {
    final Random rnd = new Random(seed);
    final RiskFixture stepwise = new RiskFixture(limits(1.0));
    final RiskFixture direct = new RiskFixture(limits(1.0));
    final RiskFixture bare = new RiskFixture(limits(1.0));
    randomBook(rnd, 6, stepwise, direct, bare);
    final Map<Integer, long[]> outstanding = new HashMap<>();
    for (int step = 0; step < 40; step++) {
      final int id = ALL[rnd.nextInt(ALL.length)];
      final long[] o = outstanding.computeIfAbsent(id, k -> new long[2]);
      if (rnd.nextInt(3) > 0) {
        final long b = rnd.nextInt(60) * M / 2;
        final long s = rnd.nextInt(60) * M / 2;
        stepwise.risk.reserve(id, b, s);
        o[0] += b;
        o[1] += s;
      } else {
        final long b = o[0] == 0 ? 0 : (long) (rnd.nextDouble() * o[0]);
        final long s = o[1] == 0 ? 0 : (long) (rnd.nextDouble() * o[1]);
        stepwise.risk.release(id, b, s);
        o[0] -= b;
        o[1] -= s;
      }
      assertEquals(o[0], stepwise.risk.pendingBuy(id));
      assertEquals(o[1], stepwise.risk.pendingSell(id));
    }
    for (final Map.Entry<Integer, long[]> e : outstanding.entrySet()) {
      direct.risk.reserve(e.getKey(), e.getValue()[0], e.getValue()[1]);
    }
    assertSameHeadroom(stepwise, direct, "built step by step or in one go");
    for (final Map.Entry<Integer, long[]> e : outstanding.entrySet()) {
      stepwise.risk.release(e.getKey(), e.getValue()[0], e.getValue()[1]);
      assertEquals(0L, stepwise.risk.pendingBuy(e.getKey()));
      assertEquals(0L, stepwise.risk.pendingSell(e.getKey()));
    }
    assertSameHeadroom(stepwise, bare, "all released is as if none was ever reserved");
  }

  /**
   * A refused check leaves positions, reservations, the ladder, P&amp;L, headroom and the rate
   * buckets as they were. The twin engine sees the same book and only the accepted checks; both must
   * then have the same quote tokens left.
   */
  @Property(tries = 120, seed = "4242")
  void aRefusedCheckChangesNothing(@ForAll final long seed) {
    final Random rnd = new Random(seed);
    final RiskLimits l = limits(0.5).quoteRatePerSecond(40.0).orderRatePerSecond(40.0).burstSeconds(1.0);
    final RiskLimits twinLimits = limits(0.5).quoteRatePerSecond(40.0).orderRatePerSecond(40.0).burstSeconds(1.0);
    final RiskFixture f = new RiskFixture(l);
    final RiskFixture twin = new RiskFixture(twinLimits);
    randomBook(rnd, 5, f, twin);
    int refused = 0;
    for (int step = 0; step < 30; step++) {
      final int id = ALL[rnd.nextInt(ALL.length)];
      final long qty = (1 + rnd.nextInt(250)) * M;
      final long px =
          Decimal9.round(Decimal9.toDouble(f.fairMid(id)) * (1.0 + (rnd.nextDouble() - 0.5) * 0.002));
      final boolean order = rnd.nextBoolean();
      final Side side = SIDES[rnd.nextInt(2)];
      final String before = snapshot(f);
      final int r =
          order
              ? f.risk.checkOrder(id, side, qty, px, step)
              : f.risk.checkQuote(id, side == Side.Buy ? px : 0L, side == Side.Buy ? qty : 0L,
                  side == Side.Sell ? px : 0L, side == Side.Sell ? qty : 0L, step);
      if (r != RiskEngine.OK) {
        refused++;
        assertEquals(before, snapshot(f), "refused with " + r + " at step " + step);
        continue;
      }
      final int t =
          order
              ? twin.risk.checkOrder(id, side, qty, px, step)
              : twin.risk.checkQuote(id, side == Side.Buy ? px : 0L, side == Side.Buy ? qty : 0L,
                  side == Side.Sell ? px : 0L, side == Side.Sell ? qty : 0L, step);
      assertEquals(RiskEngine.OK, t, "the twin accepts what the engine accepted");
      if (rnd.nextBoolean()) {
        final long signed = side == Side.Buy ? qty : -qty;
        f.risk.onFill(id, signed, f.fairMid(id));
        twin.risk.onFill(id, signed, twin.fairMid(id));
      }
    }
    assertEquals(refused, f.risk.preTradeRejects(), "every refusal counted once");
    assertEquals(remainingQuotes(twin), remainingQuotes(f), "refusals took no tokens; " + refused + " refused");
  }

  /** Loosening every limit by the same factor never refuses what the tighter set accepted. */
  @Property(tries = 150, seed = "99")
  void looserLimitsNeverRefuseWhatTighterLimitsAccept(
      @ForAll final long seed, @ForAll @DoubleRange(min = 1.0, max = 3.0) final double looser) {
    final Random rnd = new Random(seed);
    final RiskFixture tight = new RiskFixture(limits(0.6));
    final RiskFixture loose = new RiskFixture(limits(0.6 * looser));
    randomBook(rnd, 8, tight, loose);
    for (int n = 0; n < 4; n++) {
      final int id = ALL[rnd.nextInt(ALL.length)];
      final long b = rnd.nextInt(40) * M;
      final long s = rnd.nextInt(40) * M;
      tight.risk.reserve(id, b, s);
      loose.risk.reserve(id, b, s);
    }
    for (final int id : ALL) {
      for (final Side side : SIDES) {
        final long tightRoom = tight.risk.headroom(id, side);
        assertTrue(loose.risk.headroom(id, side) >= tightRoom - 1, "headroom shrank for " + id + " " + side);
      }
    }
    for (int step = 0; step < 25; step++) {
      final int id = ALL[rnd.nextInt(ALL.length)];
      final Side side = SIDES[rnd.nextInt(2)];
      final long qty = (1 + rnd.nextInt(300)) * M;
      if (tight.risk.checkOrder(id, side, qty, tight.fairMid(id), step) == RiskEngine.OK) {
        assertEquals(RiskEngine.OK, loose.risk.checkOrder(id, side, qty, loose.fairMid(id), step),
            side + " " + qty + " of " + id + " passed tight limits but not loose ones");
      }
    }
  }

  /**
   * Headroom is the exact edge: an order of exactly the headroom passes, one minor unit more is
   * refused, on any book with any reservations.
   */
  @Property(tries = 150, seed = "31337")
  void headroomIsTheLargestQuantityThatPasses(@ForAll final long seed) {
    final Random rnd = new Random(seed);
    final RiskFixture f = new RiskFixture(limits(1.0));
    randomBook(rnd, 6, f);
    for (int n = 0; n < 3; n++) {
      f.risk.reserve(ALL[rnd.nextInt(ALL.length)], rnd.nextInt(50) * M, rnd.nextInt(50) * M);
    }
    int edges = 0;
    for (final int id : ALL) {
      for (final Side side : SIDES) {
        final long h = f.risk.headroom(id, side);
        if (h > 0) {
          assertEquals(RiskEngine.OK, f.risk.checkOrder(id, side, h, f.fairMid(id), 1), "at the edge " + id + " " + side);
        }
        assertNotEquals(RiskEngine.OK, f.risk.checkOrder(id, side, h + 1, f.fairMid(id), 2), "past the edge " + id + " " + side + " " + h);
        edges++;
      }
    }
    assertEquals(2 * ALL.length, edges);
  }

  /**
   * Daily P&amp;L is the book marked at the last fair value (or the fair mid at the first fill when
   * no mark has come since) less the cash paid, all from the start of day; computed here in
   * BigDecimal from the fills alone.
   */
  @Property(tries = 200, seed = "5")
  void dailyPnlIsTheMarkToMarketOfTheFills(@ForAll final long seed) {
    final Random rnd = new Random(seed);
    final RiskFixture f = new RiskFixture();
    final Map<Integer, BigDecimal> qtyMajor = new HashMap<>();
    final Map<Integer, BigDecimal> markOf = new HashMap<>();
    final BigDecimal[] cash = {BigDecimal.ZERO, BigDecimal.ZERO};
    final MathContext mc = MathContext.DECIMAL128;
    for (int step = 0; step < 40; step++) {
      final int id = ALL[rnd.nextInt(ALL.length)];
      final Instrument inst = f.directory.byId(id);
      final int c = inst.currency() == CurrencyCode.USD ? 0 : 1;
      final BigDecimal qv = new BigDecimal(inst.quoteValue());
      final double fair = Decimal9.toDouble(f.fairMid(id));
      final long px = Decimal9.round(fair + (rnd.nextInt(201) - 100) * 0.01);
      if (rnd.nextInt(3) == 0) {
        f.risk.onFairValue(id, px);
        markOf.put(id, BigDecimal.valueOf(Decimal9.toDouble(px)));
      } else {
        final long qty = (rnd.nextInt(201) - 100) * M / 4;
        f.risk.onFill(id, qty, px);
        markOf.putIfAbsent(id, BigDecimal.valueOf(fair));
        final BigDecimal q = new BigDecimal(qty).divide(new BigDecimal(inst.minorPerMajor()), mc);
        qtyMajor.merge(id, q, BigDecimal::add);
        cash[c] = cash[c].subtract(q.multiply(BigDecimal.valueOf(Decimal9.toDouble(px))).multiply(qv), mc);
      }
    }
    final BigDecimal[] value = {cash[0], cash[1]};
    for (final Map.Entry<Integer, BigDecimal> e : qtyMajor.entrySet()) {
      final Instrument inst = f.directory.byId(e.getKey());
      final int c = inst.currency() == CurrencyCode.USD ? 0 : 1;
      value[c] = value[c].add(e.getValue().multiply(markOf.get(e.getKey())).multiply(new BigDecimal(inst.quoteValue())), mc);
    }
    assertEquals(value[0].doubleValue(), f.risk.dailyPnl(CurrencyCode.USD), 1e-6 * (1.0 + Math.abs(value[0].doubleValue())));
    assertEquals(value[1].doubleValue(), f.risk.dailyPnl(CurrencyCode.EUR), 1e-6 * (1.0 + Math.abs(value[1].doubleValue())));
    f.risk.startOfDay();
    assertEquals(0.0, f.risk.dailyPnl(CurrencyCode.USD), 1e-6);
    assertEquals(0.0, f.risk.dailyPnl(CurrencyCode.EUR), 1e-6);
  }

  private static int remainingQuotes(final RiskFixture f) {
    int n = 0;
    while (n < 100 && f.risk.checkQuote(RiskFixture.BUND, f.fairMid(RiskFixture.BUND), 1L, 0L, 0L, 0) == RiskEngine.OK) {
      n++;
    }
    return n;
  }

  private static void assertSameHeadroom(final RiskFixture a, final RiskFixture b, final String what) {
    for (final int id : ALL) {
      for (final Side side : SIDES) {
        final long ha = a.risk.headroom(id, side);
        final long hb = b.risk.headroom(id, side);
        // the envelope sums are incremental doubles; the bisection may land one minor unit apart
        assertTrue(Math.abs(ha - hb) <= 1, what + ": " + id + " " + side + " " + ha + " vs " + hb);
      }
    }
  }

  /** Everything a check could change, by value, as text (doubles print exactly). */
  private static String snapshot(final RiskFixture f) {
    final long[] pos = new long[ALL.length];
    final long[] pend = new long[2 * ALL.length];
    final long[] room = new long[2 * ALL.length];
    for (int n = 0; n < ALL.length; n++) {
      pos[n] = f.risk.position(ALL[n]);
      pend[2 * n] = f.risk.pendingBuy(ALL[n]);
      pend[2 * n + 1] = f.risk.pendingSell(ALL[n]);
      room[2 * n] = f.risk.headroom(ALL[n], Side.Buy);
      room[2 * n + 1] = f.risk.headroom(ALL[n], Side.Sell);
    }
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    f.risk.ladder(CurrencyCode.USD, v);
    final double[] lad = new double[KeyRateTenors.COUNT];
    for (int k = 0; k < lad.length; k++) {
      lad[k] = v.get(k);
    }
    return "positions " + Arrays.toString(pos)
        + " reservations " + Arrays.toString(pend)
        + " ladder " + Arrays.toString(lad)
        + " pnl " + f.risk.dailyPnl(CurrencyCode.USD)
        + " headroom " + Arrays.toString(room);
  }
}
