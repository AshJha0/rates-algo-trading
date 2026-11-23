package io.ratesalgo.risk;

import static io.ratesalgo.risk.RiskFixture.BOND10;
import static io.ratesalgo.risk.RiskFixture.BOND2;
import static io.ratesalgo.risk.RiskFixture.FUT10;
import static io.ratesalgo.risk.RiskFixture.M;
import static io.ratesalgo.risk.RiskFixture.SWAP10;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.messages.sbe.Side;
import java.util.Random;
import java.util.function.DoubleConsumer;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * A book already past a PV01 limit may not add to that breach by any amount, for every limit kind
 * (pillar, net, gross, basis, swap spread). The book is random; the limits are then set below what
 * it holds, so the breaches are certain. The figures are recomputed here from the sensitivity
 * provider, positions and reservations, not read from the engine's incremental sums. An increase
 * counts when it is above 1e-7 of PV01 absolute: well above this test's own rounding at these
 * sizes, and well below the 8.2e-6 that one minor unit of the 10Y adds (the case that a 1e-9
 * relative allowance used to let through). Headroom stays the exact edge on such a book.
 */
class BreachedLimitPropertiesTest {

  private static final int[] USD = {BOND10, FUT10, SWAP10, BOND2};
  private static final Side[] SIDES = {Side.Buy, Side.Sell};
  private static final int K = KeyRateTenors.COUNT;
  private static final double EPS = 1e-7;

  private static final int BUCKET = 1;
  private static final int NET = 1 << 1;
  private static final int GROSS = 1 << 2;
  private static final int BASIS = 1 << 3;
  private static final int SPREAD = 1 << 4;

  @Property(tries = 300, seed = "20261123")
  void noOrderAddsToABreachedPv01FigureAndHeadroomIsStillTheEdge(
      @ForAll @IntRange(min = 1, max = 31) final int kinds, @ForAll final long seed) {
    final Random rnd = new Random(seed);
    final RiskFixture f = new RiskFixture(new RiskLimits().dailyLoss(1e18));
    final int fills = 1 + rnd.nextInt(5);
    for (int n = 0; n < fills; n++) {
      final int id = USD[rnd.nextInt(USD.length)];
      f.risk.onFill(id, (rnd.nextInt(401) - 200) * M + rnd.nextInt(1000), f.fairMid(id));
    }
    final int reservations = rnd.nextInt(4);
    for (int n = 0; n < reservations; n++) {
      f.risk.reserve(USD[rnd.nextInt(USD.length)], rnd.nextInt(80) * M + rnd.nextInt(7), rnd.nextInt(80) * M);
    }

    final Figures before = figures(f, -1, 0L, 0L);
    boolean breached = false;
    if ((kinds & BUCKET) != 0) {
      for (int k = 0; k < K; k++) {
        final double g = Math.max(before.hi[k], -before.lo[k]);
        if (g > 1.0) {
          f.limits.bucketPv01(k, g * rnd.nextDouble(0.3, 0.98));
          breached = true;
        }
      }
    }
    breached |= set(kinds, NET, Math.max(before.netHi, -before.netLo), rnd, f.limits::netPv01);
    breached |= set(kinds, GROSS, before.gross(), rnd, f.limits::grossPv01);
    breached |= set(kinds, BASIS, before.basis, rnd, f.limits::basisPv01);
    breached |= set(kinds, SPREAD, before.spread, rnd, f.limits::swapSpreadPv01);

    long seq = 0;
    int adding = 0;
    for (int j = 0; j < USD.length; j++) {
      final int id = USD[j];
      for (final Side side : SIDES) {
        final long[] sizes = {1L, 37L, 100L, 1 + rnd.nextLong(50 * M)};
        for (final long q : sizes) {
          final Figures after = figures(f, j, side == Side.Buy ? q : 0L, side == Side.Buy ? 0L : q);
          final String what = side + " " + q + " of " + id + ", kinds " + kinds;
          final String grows = grows(f.limits, before, after);
          final int r = f.risk.checkOrder(id, side, q, f.fairMid(id), ++seq);
          if (grows != null) {
            adding++;
            assertNotEquals(RiskEngine.OK, r, what + " adds to the breached " + grows);
          }
        }
        final long h = f.risk.headroom(id, side);
        final String where = "kinds " + kinds + " " + id + " " + side + " h " + h;
        if (h > 0) {
          assertEquals(RiskEngine.OK, f.risk.checkOrder(id, side, h, f.fairMid(id), ++seq), "at the edge, " + where);
        }
        if (h < Long.MAX_VALUE) {
          assertNotEquals(RiskEngine.OK, f.risk.checkOrder(id, side, h + 1, f.fairMid(id), ++seq), "past it, " + where);
        }
      }
    }
    // a breached figure can always be pushed further by some order, so the property was exercised
    assertTrue(!breached || adding > 0, "no order added to a breach, kinds " + kinds);
  }

  /** Sets one limit below a figure past 1.0 when its kind is chosen; true when it did. */
  private static boolean set(
      final int kinds, final int kind, final double figure, final Random rnd, final DoubleConsumer limit) {
    if ((kinds & kind) == 0 || figure <= 1.0) {
      return false;
    }
    limit.accept(figure * rnd.nextDouble(0.3, 0.98));
    return true;
  }

  /** The first breached figure the order raises by more than {@link #EPS}, or null. */
  private static String grows(final RiskLimits l, final Figures b, final Figures a) {
    for (int k = 0; k < K; k++) {
      if (raises(b.hi[k], a.hi[k], l.bucketPv01(k)) || raises(-b.lo[k], -a.lo[k], l.bucketPv01(k))) {
        return "pillar " + k;
      }
    }
    if (raises(b.netHi, a.netHi, l.netPv01()) || raises(-b.netLo, -a.netLo, l.netPv01())) {
      return "net";
    }
    if (raises(b.gross(), a.gross(), l.grossPv01())) {
      return "gross";
    }
    if (raises(b.basis, a.basis, l.basisPv01())) {
      return "basis";
    }
    return raises(b.spread, a.spread, l.swapSpreadPv01()) ? "swap spread" : null;
  }

  private static boolean raises(final double was, final double is, final double limit) {
    return was > limit && is > was + EPS;
  }

  /**
   * Worst-case figures with instrument {@code j} carrying {@code buy} and {@code sell} more than
   * its reservation ({@code j} of -1 for none). Pillar and net extremes take each reservation at
   * whichever side is worse, independently per instrument; basis and swap spread use positions
   * plus the order alone, as the engine documents.
   */
  private static Figures figures(final RiskFixture f, final int j, final long buy, final long sell) {
    final Figures out = new Figures();
    final double[] product = new double[ProductType.COUNT];
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    for (int n = 0; n < USD.length; n++) {
      final int id = USD[n];
      final double[] pos = pv01(f, id, f.risk.position(id), v);
      final double[] up = pv01(f, id, f.risk.pendingBuy(id) + (n == j ? buy : 0L), v);
      final double[] down = pv01(f, id, -(f.risk.pendingSell(id) + (n == j ? sell : 0L)), v);
      double posPar = 0.0;
      double upPar = 0.0;
      double downPar = 0.0;
      for (int k = 0; k < K; k++) {
        out.hi[k] += pos[k] + Math.max(0.0, Math.max(up[k], down[k]));
        out.lo[k] += pos[k] + Math.min(0.0, Math.min(up[k], down[k]));
        posPar += pos[k];
        upPar += up[k];
        downPar += down[k];
      }
      out.netHi += posPar + Math.max(0.0, Math.max(upPar, downPar));
      out.netLo += posPar + Math.min(0.0, Math.min(upPar, downPar));
      final int t = f.directory.byId(id).type().index();
      product[t] += posPar;
      if (n == j) {
        final long signed = buy - sell;
        final double[] order = pv01(f, id, signed, v);
        double orderPar = 0.0;
        for (int k = 0; k < K; k++) {
          orderPar += order[k];
        }
        product[t] += orderPar;
      }
    }
    final double fut = product[ProductType.FUTURE.index()];
    final double bond = product[ProductType.BOND.index()];
    out.basis = offset(fut, bond);
    out.spread = offset(product[ProductType.SWAP.index()], bond + fut);
    return out;
  }

  private static double[] pv01(final RiskFixture f, final int id, final long qty, final Dv01Vector v) {
    final double[] out = new double[K];
    if (qty != 0L) {
      f.sensitivities.pv01(id, qty, v);
      for (int k = 0; k < K; k++) {
        out[k] = v.get(k);
      }
    }
    return out;
  }

  /** Size of the PV01 two legs offset each other by: zero when they have the same sign. */
  private static double offset(final double a, final double b) {
    return a * b >= 0.0 ? 0.0 : Math.min(Math.abs(a), Math.abs(b));
  }

  private static final class Figures {
    final double[] hi = new double[K];
    final double[] lo = new double[K];
    double netHi;
    double netLo;
    double basis;
    double spread;

    double gross() {
      double g = 0.0;
      for (int k = 0; k < K; k++) {
        g += Math.max(Math.abs(hi[k]), Math.abs(lo[k]));
      }
      return g;
    }
  }
}
