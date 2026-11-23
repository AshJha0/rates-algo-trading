package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.Decimal9;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.RiskEngine;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

/**
 * Invariants of every quote the engine builds, over random configurations, books and requests:
 * the price is rounded in the dealer's favour, a bid is at or under fair and an offer at or over
 * it (to within the configured improvement), the half-spread stays inside its clamp, the quote on
 * the wire is the quote in the outcome, and nothing is quoted while killed or unhealthy.
 */
class QuoteConstructionPropertyTest {

  private static final int HEARTBEAT_SOURCE = 9;

  @Property(tries = 150)
  void everyQuoteIsRoundedOurWayInsideItsClampAndOnTheRightSideOfFair(
      @ForAll final long seed,
      @ForAll @DoubleRange(min = 0.0, max = 0.5) final double minHalf,
      @ForAll @DoubleRange(min = 0.6, max = 12.0) final double maxHalf,
      @ForAll @DoubleRange(min = 0.0, max = 2.0) final double maxSkew,
      @ForAll @IntRange(min = 0, max = 2) final int improveCase,
      @ForAll @IntRange(min = 0, max = 300) final int bookMillions) {
    final double maxImprove = improveCase == 0 ? 0.0 : 0.25 * improveCase;
    final QuotingConfig config =
        new QuotingConfig()
            .minHalfSpreadBp(minHalf)
            .maxHalfSpreadBp(maxHalf)
            .maxSkewBp(maxSkew)
            .maxImproveBp(maxImprove)
            .requoteThrottleNanos(0L);
    final TestMarket m = new TestMarket(TestMarket.standardLimits(), config, Hedger.config());
    final QuotingEngine engine = m.desk.quoting();
    final RiskEngine risk = m.desk.risk();
    final Random rnd = new Random(seed);
    // a book that leans one way or the other, so the skew is exercised on both sides
    risk.onFill(TestMarket.UST10, (bookMillions - 150) * FACE_1M, m.fair.fairMid(TestMarket.UST10));
    final QuoteDecoder wire = new QuoteDecoder();
    int quoted = 0;
    for (int n = 1; n <= 80; n++) {
      final int id = TestMarket.RFQ_INSTRUMENTS[rnd.nextInt(TestMarket.RFQ_INSTRUMENTS.length)];
      final Side clientSide = rnd.nextBoolean() ? Side.Buy : Side.Sell;
      final long qty = (1 + rnd.nextInt(60)) * FACE_1M;
      final int client = 1 + rnd.nextInt(3);
      final int sent = m.gateway.count(QuoteDecoder.TEMPLATE_ID);
      final RfqDecision d = engine.onQuoteRequest(7, n, client, id, clientSide, qty, 2 + rnd.nextInt(4), 0L, 0L);
      final RfqOutcome o = engine.outcome();
      if (d == RfqDecision.PASS) {
        assertEquals(sent, m.gateway.count(QuoteDecoder.TEMPLATE_ID), "a pass sends nothing");
        continue;
      }
      quoted++;
      final boolean weBuy = clientSide == Side.Sell;
      final double qpb = risk.quotePerBp(id);
      final double price = Decimal9.toDouble(o.price());
      final double target = weBuy ? o.fair() - o.distanceBp() * qpb : o.fair() + o.distanceBp() * qpb;
      // dealer-favourable rounding: never better for the client than the unrounded price, and
      // within one Decimal9 unit of it
      if (weBuy) {
        assertTrue(price <= target + 1e-12, "bid rounded up: " + price + " > " + target);
        assertTrue(price > target - 2e-9, "bid more than a unit under its target");
      } else {
        assertTrue(price >= target - 1e-12, "offer rounded down: " + price + " < " + target);
        assertTrue(price < target + 2e-9, "offer more than a unit over its target");
      }
      // side of fair
      final double slack = maxImprove * qpb + 1e-12;
      assertTrue(weBuy ? price <= o.fair() + slack : price >= o.fair() - slack, "through fair by more than maxImprove");
      if (maxImprove == 0.0) {
        assertTrue(weBuy ? price <= o.fair() : price >= o.fair(), "bid <= fair <= ask");
        assertTrue(o.distanceBp() >= 0.0);
      }
      // clamps
      assertTrue(o.halfSpreadBp() >= minHalf - 1e-12 && o.halfSpreadBp() <= maxHalf + 1e-12, "half " + o.halfSpreadBp());
      assertTrue(Math.abs(o.skewBp()) <= maxSkew + 1e-12);
      assertTrue(o.sizeTermBp() >= 0.0 && o.sizeTermBp() <= maxSkew + 1e-12);
      assertTrue(o.axeBp() >= 0.0 && o.axeBp() <= maxSkew + 1e-12);
      assertTrue(o.distanceBp() >= -maxImprove - 1e-12);
      assertTrue(o.costBp() < maxHalf, "a quote whose cost reaches the cap is a pass");
      assertTrue(o.quantity() > 0 && o.quantity() <= qty && o.quantity() % FACE_1M == 0);
      // the wire carries the outcome, one-sided
      assertEquals(sent + 1, m.gateway.count(QuoteDecoder.TEMPLATE_ID));
      wire.wrap(m.gateway.message(m.gateway.size() - 1), MessageHeaderEncoder.ENCODED_LENGTH, QuoteDecoder.BLOCK_LENGTH, 0);
      assertEquals(o.quoteId(), wire.quoteId());
      assertEquals(weBuy ? o.price() : 0L, wire.bidPrice().mantissa());
      assertEquals(weBuy ? 0L : o.price(), wire.askPrice().mantissa());
      assertEquals(weBuy ? o.quantity() : 0L, wire.bidQuantity());
      assertEquals(weBuy ? 0L : o.quantity(), wire.askQuantity());
      assertTrue(wire.validUntilNanos() == 0L || wire.validUntilNanos() <= m.clock.epochNanos() + config.firmTtlNanos());
      // some quotes trade, so the book and the skew keep moving
      if (rnd.nextInt(3) == 0) {
        engine.onQuoteResponse(o.quoteId(), io.ratesalgo.messages.sbe.QuoteStatus.Done, o.price(), o.quantity(), 0L);
      }
      if (rnd.nextInt(8) == 0) {
        m.clock.advance(300_000_000L);
        m.refreshAll(m.clock.epochNanos());
      }
    }
    assertTrue(quoted >= 0);
  }

  @Property(tries = 100)
  void nothingIsQuotedWhileKilledOrUnhealthy(
      @ForAll final long seed, @ForAll @IntRange(min = 0, max = 3) final int unhealthyBy) {
    final TestMarket m = new TestMarket(TestMarket.standardLimits(), new QuotingConfig().requoteThrottleNanos(0L), Hedger.config());
    final QuotingEngine engine = m.desk.quoting();
    final InputHealth health = m.desk.health();
    health.requireHeartbeat(HEARTBEAT_SOURCE, m.clock.epochNanos());
    health.trackVenue(7);
    final Random rnd = new Random(seed);
    boolean killed = false;
    boolean down = false;
    boolean gap = false;
    long lastBeat = m.clock.epochNanos();
    long seq = 1;
    health.onBookSequence(7, seq);
    for (int n = 1; n <= 120; n++) {
      switch (rnd.nextInt(8)) {
        case 0 -> {
          if (!killed && unhealthyBy == 0) {
            m.desk.killSwitch().trigger(KillReason.Manual, 0, 1);
            killed = true;
          }
        }
        case 1 -> {
          if (unhealthyBy == 1) {
            down = !down;
            health.onSession(7, !down);
          }
        }
        case 2 -> {
          if (unhealthyBy == 2) {
            if (gap) {
              health.onFeedRecovered(7);
            } else {
              seq += 5;
              health.onBookSequence(7, seq);
            }
            gap = !gap;
          }
        }
        case 3 -> {
          // time passes with or without a heartbeat
          m.clock.advance(1_000_000_000L);
          m.refreshAll(m.clock.epochNanos());
          if (unhealthyBy != 3 || rnd.nextBoolean()) {
            lastBeat = m.clock.epochNanos();
            health.onHeartbeat(HEARTBEAT_SOURCE, lastBeat);
          }
        }
        default -> {
          // an RFQ
        }
      }
      final boolean silent = m.clock.epochNanos() - lastBeat > 3_000_000_000L;
      final int id = TestMarket.RFQ_INSTRUMENTS[rnd.nextInt(TestMarket.RFQ_INSTRUMENTS.length)];
      final int sent = m.gateway.count(QuoteDecoder.TEMPLATE_ID);
      final RfqDecision d =
          engine.onQuoteRequest(7, n, 1 + rnd.nextInt(2), id, rnd.nextBoolean() ? Side.Buy : Side.Sell, 5 * FACE_1M, 3, 0L, 0L);
      if (killed || down || gap || silent) {
        assertEquals(RfqDecision.PASS, d, "quoted while " + (killed ? "killed" : "unhealthy"));
        assertEquals(killed ? PassReason.KILLED : PassReason.UNHEALTHY, engine.outcome().reason());
        assertEquals(sent, m.gateway.count(QuoteDecoder.TEMPLATE_ID));
        assertEquals(0, engine.liveQuotes(), "and every earlier quote is pulled");
      } else {
        assertEquals(sent + (d == RfqDecision.PASS ? 0 : 1), m.gateway.count(QuoteDecoder.TEMPLATE_ID));
      }
    }
  }
}
