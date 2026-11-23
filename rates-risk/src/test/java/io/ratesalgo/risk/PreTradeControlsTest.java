package io.ratesalgo.risk;

import static io.ratesalgo.risk.RiskFixture.BOND10;
import static io.ratesalgo.risk.RiskFixture.BOND2;
import static io.ratesalgo.risk.RiskFixture.FUT10;
import static io.ratesalgo.risk.RiskFixture.M;
import static io.ratesalgo.risk.RiskFixture.SWAP10;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.messages.sbe.LimitKind;
import io.ratesalgo.messages.sbe.Side;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * Review 2026-11-23, Q9 and Q12: the price collar, fat-finger quantity, per-RFQ and per-client DV01
 * limits as pre-trade controls, and the risk-reducing exemption held inside the PV01 envelope.
 */
class PreTradeControlsTest {

  private static final int[] USD = {BOND10, FUT10, SWAP10, BOND2};

  // ---------------------------------------------------------------- Q12

  /** A 10-year order that lowers parallel PV01 against 2-year risk still answers to its pillar. */
  @Test
  void aHedgeAtAnotherTenorDoesNotSkipTheBucketLimit() {
    final RiskFixture f = new RiskFixture(new RiskLimits().bucketPv01(50_000.0));
    f.risk.onFill(BOND2, 400 * M, f.fairMid(BOND2)); // -76k at 2Y, past the 50k limit
    final long px = f.fairMid(BOND10);
    // +73.8k at 10Y: net falls from -76k to -2.2k, and the 10Y pillar goes from 0 to past its limit
    assertEquals(LimitKind.BucketDv01.value(), f.risk.checkOrder(BOND10, Side.Sell, 90 * M, px, 1));
    assertEquals(1, f.alerts.count(io.ratesalgo.messages.sbe.RiskLimitBreachDecoder.TEMPLATE_ID) - 1, "one pre-trade breach after the post-trade one");
    // inside the 10Y limit the cross-tenor hedge is still allowed: +49.2k
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Sell, 60 * M, px, 2));
    // and a hedge at the tenor of the risk always passes, however far the pillar is in breach
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND2, Side.Sell, 400 * M, f.fairMid(BOND2), 3));
  }

  @Test
  void aHedgeAtAnotherTenorDoesNotSkipTheGrossLimit() {
    final RiskFixture f = new RiskFixture(new RiskLimits().grossPv01(100_000.0));
    f.risk.onFill(BOND2, 400 * M, f.fairMid(BOND2)); // gross 76k
    // +73.8k at 10Y: net nearly flat, gross 149.8k
    assertEquals(LimitKind.GrossDv01.value(), f.risk.checkOrder(BOND10, Side.Sell, 90 * M, f.fairMid(BOND10), 1));
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Sell, 25 * M, f.fairMid(BOND10), 2), "76k + 20.5k");
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND2, Side.Sell, 400 * M, f.fairMid(BOND2), 3));
  }

  /** The daily-loss exemption is what is left of the special case, and it is still there. */
  @Test
  void aReducingOrderStillSkipsTheDailyLossCheck() {
    final RiskFixture f = new RiskFixture(new RiskLimits().dailyLoss(50_000.0).killOnDailyLoss(false).bucketPv01(100_000.0));
    f.risk.startOfDay();
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10));
    f.fair.set(BOND10, Decimal9.round(97.0), f.clock.epochNanos());
    f.risk.onFairValue(BOND10, Decimal9.round(97.0));
    final long px = f.fairMid(BOND10);
    assertEquals(LimitKind.DailyLoss.value(), f.risk.checkOrder(BOND10, Side.Buy, M, px, 1));
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Sell, 50 * M, px, 2));
  }

  /**
   * For any book and any order the engine accepts, no pillar and no gross figure, recomputed from
   * positions alone, ends further past its limit than it started; an order that would do that is
   * refused whether or not it lowers parallel PV01.
   */
  @Property(tries = 300)
  void noAcceptedOrderMovesAPillarOrGrossFurtherPastItsLimit(
      @ForAll final long seed,
      @ForAll @DoubleRange(min = 5_000.0, max = 120_000.0) final double bucket,
      @ForAll @DoubleRange(min = 20_000.0, max = 400_000.0) final double gross) {
    final RiskFixture f = new RiskFixture(new RiskLimits().bucketPv01(bucket).grossPv01(gross));
    final Random rnd = new Random(seed);
    final Dv01Vector before = new Dv01Vector(CurrencyCode.USD);
    final Dv01Vector after = new Dv01Vector(CurrencyCode.USD);
    int accepted = 0;
    for (int step = 0; step < 60; step++) {
      final int id = USD[rnd.nextInt(USD.length)];
      final long qty = (1 + rnd.nextInt(150)) * M;
      final Side side = rnd.nextBoolean() ? Side.Buy : Side.Sell;
      if (rnd.nextInt(4) == 0) {
        // client flow lands whatever the limits say: this is how a book gets into breach
        f.risk.onFill(id, side == Side.Buy ? qty : -qty, f.fairMid(id));
        continue;
      }
      f.risk.fullLadder(CurrencyCode.USD, before);
      final int r = f.risk.checkOrder(id, side, qty, f.fairMid(id), step);
      if (r != RiskEngine.OK) {
        continue;
      }
      accepted++;
      f.risk.onFill(id, side == Side.Buy ? qty : -qty, f.fairMid(id));
      f.risk.fullLadder(CurrencyCode.USD, after);
      double g0 = 0.0;
      double g1 = 0.0;
      for (int k = 0; k < KeyRateTenors.COUNT; k++) {
        final double was = Math.abs(before.get(k));
        final double is = Math.abs(after.get(k));
        g0 += was;
        g1 += is;
        assertTrue(is <= Math.max(bucket, was) * (1 + 1e-9) + 1e-6,
            "pillar " + k + " " + was + " -> " + is + " limit " + bucket + " by " + side + " " + qty + " of " + id);
      }
      assertTrue(g1 <= Math.max(gross, g0) * (1 + 1e-9) + 1e-6, "gross " + g0 + " -> " + g1 + " limit " + gross);
    }
    assertTrue(accepted >= 0);
  }

  // ---------------------------------------------------------------- Q9

  @Test
  void aQuoteAboveThePerRfqDv01LimitIsRefusedAndCounted() {
    final RiskFixture f = new RiskFixture(new RiskLimits().rfqDv01(40_000.0));
    final long px = f.fairMid(BOND10);
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BOND10, px, 48 * M, 0L, 0L, 1), "39.4k");
    assertEquals(RiskEngine.REJECT_RFQ_DV01, f.risk.checkQuote(BOND10, px, 49 * M, 0L, 0L, 2), "40.2k");
    assertEquals(RiskEngine.REJECT_RFQ_DV01, f.risk.checkQuote(BOND10, 0L, 0L, px, 49 * M, 3), "either side");
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BOND2, f.fairMid(BOND2), 200 * M, 0L, 0L, 4), "38k of 2Y");
    assertEquals(2L, f.risk.dv01Rejects());
    assertEquals(2L, f.risk.preTradeRejects());
    assertEquals(0, f.alerts.size(), "the schema has no kind for it: counted, not published");
    assertEquals(48 * M, f.risk.quoteHeadroom(RiskEngine.NO_CLIENT, BOND10, Side.Buy) / M * M, "sized down to the limit");
    assertEquals(39_360.0, f.risk.dv01(BOND10, 48 * M), 1e-6);
  }

  @Test
  void aClientsDealtAndLiveDv01AreLimitedTogether() {
    final RiskFixture f = new RiskFixture(new RiskLimits().clientDv01(100_000.0));
    final long px = f.fairMid(BOND10);
    final double fifty = f.risk.dv01(BOND10, 50 * M); // 41k
    assertEquals(RiskEngine.OK, f.risk.checkQuote(7, BOND10, px, 50 * M, 0L, 0L, 1));
    f.risk.reserveClient(7, fifty);
    assertEquals(fifty, f.risk.clientOpenDv01(7), 1e-9);
    assertEquals(RiskEngine.OK, f.risk.checkQuote(7, BOND10, 0L, 0L, px, 50 * M, 2), "82k live, either side counts");
    f.risk.reserveClient(7, fifty);
    assertEquals(RiskEngine.REJECT_CLIENT_DV01, f.risk.checkQuote(7, BOND10, px, 50 * M, 0L, 0L, 3), "123k");
    assertEquals(RiskEngine.OK, f.risk.checkQuote(8, BOND10, px, 50 * M, 0L, 0L, 4), "another client is not affected");
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BOND10, px, 50 * M, 0L, 0L, 5), "nor a quote with no client");
    // one quote trades, the other expires: 41k dealt, nothing live
    f.risk.releaseClient(7, fifty);
    f.risk.onClientFill(7, fifty);
    f.risk.releaseClient(7, fifty);
    assertEquals(fifty, f.risk.clientDealtDv01(7), 1e-9);
    assertEquals(0.0, f.risk.clientOpenDv01(7), 1e-9);
    assertEquals(RiskEngine.OK, f.risk.checkQuote(7, BOND10, px, 50 * M, 0L, 0L, 6), "82k");
    final long room = f.risk.quoteHeadroom(7, BOND10, Side.Buy);
    assertEquals(71 * M, room / M * M, "59k of headroom is 71mm of the 10Y");
    f.risk.onClientFill(7, 2.0 * fifty);
    assertEquals(0L, f.risk.quoteHeadroom(7, BOND10, Side.Buy), "limit used up");
    assertEquals(RiskEngine.REJECT_CLIENT_DV01, f.risk.checkQuote(7, BOND2, f.fairMid(BOND2), M, 0L, 0L, 7));
    f.risk.startOfDay();
    assertEquals(0.0, f.risk.clientDealtDv01(7), 0.0, "a new day");
    assertEquals(RiskEngine.OK, f.risk.checkQuote(7, BOND10, px, 50 * M, 0L, 0L, 8));
    f.risk.releaseClient(99, 1.0); // an unknown client is ignored
    assertEquals(0.0, f.risk.clientOpenDv01(99), 0.0);
  }

  @Test
  void aClientTheTableCannotHoldIsRefusedNotWavedThrough() {
    final RiskFixture f = new RiskFixture(new RiskLimits().clientDv01(100_000.0));
    for (int c = 0; c < RiskEngine.MAX_CLIENTS; c++) {
      f.risk.onClientFill(1_000 + c, 1.0);
    }
    final long px = f.fairMid(BOND10);
    assertEquals(RiskEngine.OK, f.risk.checkQuote(1_000, BOND10, px, M, 0L, 0L, 1));
    assertEquals(RiskEngine.REJECT_CLIENT_DV01, f.risk.checkQuote(5, BOND10, px, M, 0L, 0L, 2));
    f.risk.reserveClient(5, 1.0); // nowhere to record it
    assertEquals(0.0, f.risk.clientOpenDv01(5), 0.0);
  }

  @Test
  void collarAndFatFingerApplyToQuotesAndOrders() {
    final RiskFixture f = new RiskFixture(new RiskLimits().priceCollarBp(10.0).fatFingerQuantity(150 * M));
    final long fair = f.fairMid(BOND10);
    final double perBp = f.risk.quotePerBp(BOND10);
    final long inside = Decimal9.round(Decimal9.toDouble(fair) + 9.9 * perBp);
    final long outside = Decimal9.round(Decimal9.toDouble(fair) + 10.1 * perBp);
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Buy, M, inside, 1));
    assertEquals(LimitKind.PriceCollar.value(), f.risk.checkOrder(BOND10, Side.Buy, M, outside, 2));
    assertEquals(LimitKind.PriceCollar.value(), f.risk.checkQuote(BOND10, 0L, 0L, outside, M, 3));
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Buy, 150 * M, fair, 4));
    assertEquals(LimitKind.FatFinger.value(), f.risk.checkOrder(BOND10, Side.Buy, 150 * M + 1, fair, 5));
    assertEquals(LimitKind.FatFinger.value(), f.risk.checkQuote(BOND10, fair, 151 * M, 0L, 0L, 6));
  }

  @Test
  void limitValidation() {
    final RiskLimits l = new RiskLimits();
    assertEquals(Double.MAX_VALUE, l.rfqDv01());
    assertEquals(Double.MAX_VALUE, l.clientDv01());
    assertEquals(5.0, l.rfqDv01(5.0).rfqDv01());
    assertEquals(6.0, l.clientDv01(6.0).clientDv01());
    assertThrows(IllegalArgumentException.class, () -> l.rfqDv01(0.0));
    assertThrows(IllegalArgumentException.class, () -> l.clientDv01(Double.NaN));
  }

  /**
   * Zero violations at the sink. Random quotes and orders at random prices and sizes for random
   * clients go through the engine; everything it accepts is checked against the limits by a model
   * that keeps its own books, and everything the model says is clean is expected to be accepted
   * unless another limit refused it.
   */
  @Property(tries = 200)
  void nothingAcceptedBreaksTheCollarTheFatFingerOrTheDv01Limits(
      @ForAll final long seed,
      @ForAll @DoubleRange(min = 2.0, max = 25.0) final double collarBp,
      @ForAll @IntRange(min = 20, max = 300) final int fatFingerMillions,
      @ForAll @DoubleRange(min = 5_000.0, max = 80_000.0) final double rfqDv01,
      @ForAll @DoubleRange(min = 20_000.0, max = 300_000.0) final double clientDv01) {
    final RiskLimits limits =
        new RiskLimits().priceCollarBp(collarBp).fatFingerQuantity(fatFingerMillions * M).rfqDv01(rfqDv01).clientDv01(clientDv01);
    final RiskFixture f = new RiskFixture(limits);
    final Random rnd = new Random(seed);
    final Map<Integer, Double> dealt = new HashMap<>();
    final Map<Integer, Double> live = new HashMap<>();
    int violations = 0;
    int wronglyRefused = 0;
    for (int step = 0; step < 400; step++) {
      final int id = USD[rnd.nextInt(USD.length)];
      final long qty = (1 + rnd.nextInt(400)) * M;
      final double perBp = f.risk.quotePerBp(id);
      final double offBp = (rnd.nextDouble() * 2.0 - 1.0) * 30.0;
      final long px = Decimal9.round(Decimal9.toDouble(f.fairMid(id)) + offBp * perBp);
      final double bp = Math.abs(Decimal9.toDouble(px) - Decimal9.toDouble(f.fairMid(id))) / perBp;
      final double dv01 = Math.abs(f.risk.unitParallelPv01(id)) * f.directory.byId(id).major(qty);
      final boolean priceOk = bp <= collarBp;
      final boolean sizeOk = qty <= fatFingerMillions * M;
      if (rnd.nextInt(3) == 0) {
        final int r = f.risk.checkOrder(id, rnd.nextBoolean() ? Side.Buy : Side.Sell, qty, px, step);
        if (r == RiskEngine.OK && !(priceOk && sizeOk)) {
          violations++;
        }
        if (r != RiskEngine.OK && priceOk && sizeOk) {
          wronglyRefused++;
        }
        continue;
      }
      final int client = 1 + rnd.nextInt(6);
      final boolean bid = rnd.nextBoolean();
      final double used = dealt.getOrDefault(client, 0.0) + live.getOrDefault(client, 0.0);
      final boolean dv01Ok = dv01 <= rfqDv01 && used + dv01 <= clientDv01;
      final int r = f.risk.checkQuote(client, id, bid ? px : 0L, bid ? qty : 0L, bid ? 0L : px, bid ? 0L : qty, step);
      if (r == RiskEngine.OK && !(priceOk && sizeOk && dv01Ok)) {
        violations++;
      }
      if (r != RiskEngine.OK && priceOk && sizeOk && dv01Ok) {
        wronglyRefused++;
      }
      if (r == RiskEngine.OK) {
        // the quote goes live, then trades, expires or stays
        f.risk.reserveClient(client, dv01);
        live.merge(client, dv01, Double::sum);
        final int fate = rnd.nextInt(3);
        if (fate < 2) {
          f.risk.releaseClient(client, dv01);
          live.merge(client, -dv01, Double::sum);
        }
        if (fate == 0) {
          f.risk.onClientFill(client, dv01);
          dealt.merge(client, dv01, Double::sum);
        }
      }
      assertEquals(dealt.getOrDefault(client, 0.0), f.risk.clientDealtDv01(client), 1e-6);
      assertEquals(live.getOrDefault(client, 0.0), f.risk.clientOpenDv01(client), 1e-6);
    }
    assertEquals(0, violations, "accepted past a limit");
    assertEquals(0, wronglyRefused, "refused inside every limit");
  }
}
