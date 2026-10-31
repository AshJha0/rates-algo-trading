package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * P3 gate: one million simulated RFQs through the engine with fills drawn from the engine's own
 * win probability, fair-value drift, and the hedger running against the same risk engine. The
 * auditor checks every message that reaches the gateway or the router; none may violate a limit.
 */
class MillionRfqTest {

  static final int RFQS = 1_000_000;

  @Test
  void oneMillionRfqsWithZeroLimitViolationsAtTheSink() {
    final RiskLimits limits = TestMarket.standardLimits();
    final SinkAuditor auditor = new SinkAuditor(limits);
    final TestMarket m =
        new TestMarket(limits, new QuotingConfig(), Hedger.config().triggerStdDev(50_000.0), null, auditor);
    auditor.bind(m);
    final QuotingEngine engine = m.desk.quoting();
    final RiskEngine risk = m.desk.risk();
    final SplittableRandom rnd = new SplittableRandom(20261031L);
    final Map<PassReason, Long> passes = new EnumMap<>(PassReason.class);
    long won = 0;
    for (int n = 1; n <= RFQS; n++) {
      m.clock.advance(2_000_000L);
      final long now = m.clock.epochNanos();
      if (n % 50 == 0) {
        m.refreshAll(now);
      }
      if (n % 5_000 == 0) {
        m.shift(rnd.nextBoolean() ? CurrencyCode.USD : CurrencyCode.EUR, rnd.nextDouble(-1.0, 1.0));
      }
      final int id = TestMarket.RFQ_INSTRUMENTS[rnd.nextInt(TestMarket.RFQ_INSTRUMENTS.length)];
      final Side side = rnd.nextBoolean() ? Side.Buy : Side.Sell;
      final long qty = (1 + rnd.nextInt(100)) * FACE_1M;
      final RfqDecision d = engine.onQuoteRequest(7, n, client(rnd), id, side, qty, 2 + rnd.nextInt(5), 0L, n);
      if (d == RfqDecision.PASS) {
        passes.merge(engine.outcome().reason(), 1L, Long::sum);
      } else {
        final RfqOutcome o = engine.outcome();
        final double u = rnd.nextDouble();
        // below 0.05 there is no answer and the quote expires at its time to live
        if (u >= 0.05 && u < 0.05 + 0.95 * o.winProbability()) {
          engine.onQuoteResponse(o.quoteId(), QuoteStatus.Done, o.price(), o.quantity(), 0L);
          won++;
        } else if (u >= 0.05) {
          engine.onQuoteResponse(o.quoteId(), QuoteStatus.TradedAway, 0L, 0L, 0L);
        }
      }
      if (n % 500 == 0) {
        for (final CurrencyCode c : new CurrencyCode[] {CurrencyCode.USD, CurrencyCode.EUR}) {
          final Hedger h = m.desk.hedger(c);
          h.onTimer(now);
          for (int p = h.workingParents() - 1; p >= 0; p--) {
            final long pid = h.workingParentId(p);
            final long rem = h.remaining(pid);
            final int inst = (int) auditor.parents.stream().filter(a -> a[0] == pid).findFirst().orElseThrow()[1];
            h.onHedgeFill(pid, m.fair.fairMid(inst), Math.abs(rem));
          }
        }
        auditor.parents.clear();
      }
    }
    m.clock.advance(10_000_000_000L);
    engine.poll(m.clock.epochNanos());
    System.out.println(
        "MillionRfqTest: rfqs="
            + engine.rfqs()
            + " quotes="
            + auditor.quotesSeen
            + " won="
            + won
            + " parents="
            + auditor.parentsSeen
            + " riskRejects="
            + risk.preTradeRejects()
            + " passes="
            + passes);
    assertEquals(List.of(), auditor.violations(), "violations at the sink");
    assertEquals(RFQS, engine.rfqs());
    assertTrue(auditor.quotesSeen > RFQS / 3, "quoted " + auditor.quotesSeen);
    assertTrue(passes.getOrDefault(PassReason.NO_RISK_CAPACITY, 0L) > 0, "the envelope must bind");
    assertTrue(won > 20_000, "won " + won);
    assertTrue(m.desk.hedger(CurrencyCode.USD).parentsSent() > 0);
    assertEquals(0, engine.heldQuotes());
    final Dv01Vector inc = new Dv01Vector(CurrencyCode.USD);
    final Dv01Vector full = new Dv01Vector(CurrencyCode.USD);
    risk.ladder(CurrencyCode.USD, inc);
    risk.fullLadder(CurrencyCode.USD, full);
    for (int k = 0; k < 8; k++) {
      assertEquals(full.get(k), inc.get(k), 1e-6 * 1e9, "pillar " + k);
    }

  }

  /** Clients 1 to 3 have their own tiers; 5 to 204 get the default tier (4 is blocked). */
  private static int client(final SplittableRandom rnd) {
    final int c = rnd.nextInt(203);
    return c < 3 ? 1 + c : 5 + (c - 3);
  }
}
