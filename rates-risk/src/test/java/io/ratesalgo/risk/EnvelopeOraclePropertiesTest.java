package io.ratesalgo.risk;

import static io.ratesalgo.risk.RiskFixture.BOND10;
import static io.ratesalgo.risk.RiskFixture.BOND2;
import static io.ratesalgo.risk.RiskFixture.FUT10;
import static io.ratesalgo.risk.RiskFixture.M;
import static io.ratesalgo.risk.RiskFixture.SWAP10;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.messages.sbe.LimitKind;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.RiskLimitBreachDecoder;
import io.ratesalgo.messages.sbe.Side;
import java.util.Arrays;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

/**
 * The pillar, net and gross PV01 checks against a brute-force oracle. The oracle keeps its own
 * positions and reservations, asks the sensitivity provider directly for every instrument's PV01,
 * enumerates every way the reservations can fill (each one not at all, its buy side or its sell
 * side: 3^4 = 81 scenarios) and takes the extremes per pillar and of the net. The documented rule
 * (API_SPEC.md Section 13.2, PNL_AUDIT item 14) is then applied to those extremes: each pillar and
 * net extreme, and the gross bound (sum over pillars of the larger absolute extreme), may not end
 * past its limit unless it was already there and does not move further. Cases within a relative
 * 1e-7 of any threshold are skipped, so double rounding never decides a verdict.
 */
class EnvelopeOraclePropertiesTest {

  private static final int[] USD = {BOND10, FUT10, SWAP10, BOND2};
  private static final int K = KeyRateTenors.COUNT;

  @Property(tries = 250, seed = "777")
  void pillarNetAndGrossChecksMatchTheWorstCaseOverEveryFill(
      @ForAll final long seed,
      @ForAll @DoubleRange(min = 20_000.0, max = 120_000.0) final double bucket,
      @ForAll @DoubleRange(min = 30_000.0, max = 200_000.0) final double net,
      @ForAll @DoubleRange(min = 60_000.0, max = 400_000.0) final double gross) {
    final Random rnd = new Random(seed);
    final RiskFixture f =
        new RiskFixture(new RiskLimits().bucketPv01(bucket).netPv01(net).grossPv01(gross).dailyLoss(1e15));
    final long[] pos = new long[USD.length];
    final long[] buy = new long[USD.length];
    final long[] sell = new long[USD.length];
    for (int step = 0; step < 30; step++) {
      final int j = rnd.nextInt(USD.length);
      final int id = USD[j];
      switch (rnd.nextInt(6)) {
        case 0 -> {
          final long q = (rnd.nextInt(241) - 120) * M;
          f.risk.onFill(id, q, f.fairMid(id));
          pos[j] += q;
        }
        case 1 -> {
          final long b = rnd.nextInt(50) * M;
          final long s = rnd.nextInt(50) * M;
          f.risk.reserve(id, b, s);
          buy[j] += b;
          sell[j] += s;
        }
        case 2 -> {
          final long b = buy[j] / 2;
          final long s = sell[j] / 3;
          f.risk.release(id, b, s);
          buy[j] -= b;
          sell[j] -= s;
        }
        case 3 -> {
          f.sensitivities.shiftCurve(CurrencyCode.USD, (rnd.nextDouble() - 0.5) * 40.0);
          f.risk.onCurveMove(CurrencyCode.USD);
        }
        default -> {
          final Side side = rnd.nextBoolean() ? Side.Buy : Side.Sell;
          final long q = (1 + rnd.nextInt(150)) * M;
          check(f, pos, buy, sell, j, side, q, bucket, net, gross, step);
        }
      }
    }
  }

  private static void check(
      final RiskFixture f,
      final long[] pos,
      final long[] buy,
      final long[] sell,
      final int j,
      final Side side,
      final long q,
      final double bucket,
      final double net,
      final double gross,
      final int seq) {
    final Extremes before = extremes(f, pos, buy, sell);
    final long[] buyAfter = buy.clone();
    final long[] sellAfter = sell.clone();
    if (side == Side.Buy) {
      buyAfter[j] += q;
    } else {
      sellAfter[j] += q;
    }
    final Extremes after = extremes(f, pos, buyAfter, sellAfter);
    final Verdict v = new Verdict();
    for (int k = 0; k < K && v.kind == null; k++) {
      final boolean hiOver = v.over(after.hi[k], before.hi[k], bucket);
      if (hiOver || v.over(-after.lo[k], -before.lo[k], bucket)) {
        v.set(LimitKind.BucketDv01, k, bucket, hiOver ? after.hi[k] : after.lo[k]);
      }
    }
    if (v.kind == null) {
      final boolean hiOver = v.over(after.netHi, before.netHi, net);
      if (hiOver || v.over(-after.netLo, -before.netLo, net)) {
        v.set(LimitKind.NetDv01, CurrencyCode.USD.index(), net, hiOver ? after.netHi : after.netLo);
      }
    }
    if (v.kind == null && v.over(after.gross(), before.gross(), gross)) {
      v.set(LimitKind.GrossDv01, CurrencyCode.USD.index(), gross, after.gross());
    }
    final int id = USD[j];
    final int sent = f.alerts.size();
    final int r = f.risk.checkOrder(id, side, q, f.fairMid(id), seq);
    if (v.ambiguous) {
      return;
    }
    final String what = side + " " + q / M + "mm of " + id + " seq " + seq;
    if (v.kind == null) {
      assertEquals(RiskEngine.OK, r, "oracle accepts " + what);
      assertEquals(sent, f.alerts.size());
      return;
    }
    assertEquals(v.kind.value(), r, "oracle refuses with " + v.kind + ": " + what);
    assertEquals(sent + 1, f.alerts.size(), "one breach message");
    final RiskLimitBreachDecoder d = new RiskLimitBreachDecoder();
    d.wrap(f.alerts.message(sent), MessageHeaderEncoder.ENCODED_LENGTH, RiskLimitBreachDecoder.BLOCK_LENGTH, 0);
    assertEquals(v.kind, d.limitKind());
    assertEquals(v.scope, d.scopeId(), what);
    assertEquals(seq, d.rejectedMessageSeqNo());
    assertEquals(v.limit, Decimal9.toDouble(d.limitValue().mantissa()), 1e-6);
    assertEquals(v.observed, Decimal9.toDouble(d.observedValue().mantissa()), 1e-6 * (1.0 + Math.abs(v.observed)), what);
  }

  /** Per-pillar and net extremes over every fill scenario of the reservations. */
  private static Extremes extremes(final RiskFixture f, final long[] pos, final long[] buy, final long[] sell) {
    final double[][] base = new double[USD.length][];
    final double[][] up = new double[USD.length][];
    final double[][] down = new double[USD.length][];
    for (int j = 0; j < USD.length; j++) {
      base[j] = pv01(f, USD[j], pos[j]);
      up[j] = pv01(f, USD[j], buy[j]);
      down[j] = pv01(f, USD[j], -sell[j]);
    }
    final Extremes e = new Extremes();
    Arrays.fill(e.hi, Double.NEGATIVE_INFINITY);
    Arrays.fill(e.lo, Double.POSITIVE_INFINITY);
    e.netHi = Double.NEGATIVE_INFINITY;
    e.netLo = Double.POSITIVE_INFINITY;
    int scenarios = 1;
    for (int j = 0; j < USD.length; j++) {
      scenarios *= 3;
    }
    for (int s = 0; s < scenarios; s++) {
      double total = 0.0;
      final double[] ladder = new double[K];
      int code = s;
      for (int j = 0; j < USD.length; j++) {
        final int choice = code % 3;
        code /= 3;
        for (int k = 0; k < K; k++) {
          ladder[k] += base[j][k] + (choice == 1 ? up[j][k] : choice == 2 ? down[j][k] : 0.0);
        }
      }
      for (int k = 0; k < K; k++) {
        e.hi[k] = Math.max(e.hi[k], ladder[k]);
        e.lo[k] = Math.min(e.lo[k], ladder[k]);
        total += ladder[k];
      }
      e.netHi = Math.max(e.netHi, total);
      e.netLo = Math.min(e.netLo, total);
    }
    return e;
  }

  private static double[] pv01(final RiskFixture f, final int id, final long qty) {
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    f.sensitivities.pv01(id, qty, v);
    final double[] out = new double[K];
    for (int k = 0; k < K; k++) {
      out[k] = qty == 0 ? 0.0 : v.get(k);
    }
    return out;
  }

  private static final class Extremes {
    final double[] hi = new double[K];
    final double[] lo = new double[K];
    double netHi;
    double netLo;

    double gross() {
      double g = 0.0;
      for (int k = 0; k < K; k++) {
        g += Math.max(Math.abs(hi[k]), Math.abs(lo[k]));
      }
      return g;
    }
  }

  private static final class Verdict {
    LimitKind kind;
    int scope;
    double limit;
    double observed;
    boolean ambiguous;

    /** Whether {@code value} is past what is allowed; notes a case too close to call. */
    boolean over(final double value, final double was, final double limit) {
      final double allowed = Math.max(limit, was);
      final double scale = 1.0 + Math.abs(allowed) + Math.abs(value);
      if (Math.abs(value - allowed) <= 1e-7 * scale || Math.abs(was - limit) <= 1e-7 * scale) {
        ambiguous = true;
      }
      return value > allowed;
    }

    void set(final LimitKind k, final int s, final double l, final double o) {
      kind = k;
      scope = s;
      limit = l;
      observed = o;
    }
  }
}
