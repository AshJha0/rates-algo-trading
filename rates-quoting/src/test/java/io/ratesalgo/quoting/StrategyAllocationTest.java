package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CountingOutboundSink;
import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * The QuotingAllocationTest gate for the Round 5 desk ({@link StrategyConfig#allOn} with the fix
 * round's quote protection): cover pricing and learning, the reservation price with working
 * hedges, the factor planner's hedge cost per RFQ, tick rounding, five markout horizons and the
 * volatility time to live allocate nothing once warmed up. Same windows and bounds as the default
 * path's test (DR-P3-08).
 */
class StrategyAllocationTest {

  private static final int WARM = 100_000;
  private static final int MEASURED = 20_000;
  private static final int WINDOWS = 5;

  private long rfqId;

  @Test
  void theStrategyPathAllocatesNothingAfterWarmUp() {
    final CountingOutboundSink gateway = new CountingOutboundSink();
    final QuotingConfig config =
        new QuotingConfig()
            .fairMoveWithdrawFraction(0.5)
            .rejectMismatchedDone(true)
            .volatilityReference(CurrencyCode.USD, TestMarket.UST10)
            .strategy(StrategyConfig.allOn());
    final TestMarket m =
        new TestMarket(
            TestMarket.standardLimits().defaultMaxPosition(1_000_000 * FACE_1M).bucketPv01(1e12).netPv01(1e12).grossPv01(1e12),
            config, Hedger.config(), gateway, null);
    final com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    assertTrue(mx.isThreadAllocatedMemorySupported());
    m.control.keep(false);
    run(m, WARM);
    final long sentBefore = gateway.count(QuoteDecoder.TEMPLATE_ID);
    long min = Long.MAX_VALUE;
    long max = 0L;
    final StringBuilder windows = new StringBuilder(256);
    for (int w = 0; w < WINDOWS; w++) {
      final long before = mx.getCurrentThreadAllocatedBytes();
      run(m, MEASURED);
      final long bytes = mx.getCurrentThreadAllocatedBytes() - before;
      min = Math.min(min, bytes);
      max = Math.max(max, bytes);
      windows.append(bytes).append(w + 1 < WINDOWS ? "," : "");
    }
    final long quoted = gateway.count(QuoteDecoder.TEMPLATE_ID) - sentBefore;
    System.out.println(
        "StrategyAllocationTest: " + WINDOWS + " windows of " + MEASURED + " RFQs, " + quoted + " quotes, bytes per window [" + windows + "]");
    assertTrue(quoted > WINDOWS * MEASURED / 2, "quoted " + quoted);
    assertTrue(m.desk.quoting().cover().observations() > 0, "the cover model learned");
    assertTrue(m.desk.quoting().markouts().count(5, true, 4) > 0, "markouts reached the five-minute horizon");
    assertEquals(0L, min, "a steady-state window allocates nothing");
    assertTrue(max < 4_096L, "no window allocates per RFQ: max " + max);
  }

  private void run(final TestMarket m, final int count) {
    final QuotingEngine engine = m.desk.quoting();
    for (int n = 0; n < count; n++) {
      m.clock.advance(20_000_000L);
      final long now = m.clock.epochNanos();
      if (n % 50 == 0) {
        m.refreshAll(now);
      }
      final int id = TestMarket.RFQ_INSTRUMENTS[n % TestMarket.RFQ_INSTRUMENTS.length];
      final Side side = (n / 10) % 2 == 0 ? Side.Buy : Side.Sell;
      final int client = 5 + (n / 20) % 10;
      final RfqDecision d = engine.onQuoteRequest(7, ++rfqId, client, id, side, (1 + n % 50) * FACE_1M, 3 + n % 3, 0L, rfqId);
      if (d != RfqDecision.PASS) {
        final RfqOutcome o = engine.outcome();
        if (n % 3 == 0) {
          engine.onQuoteResponse(o.quoteId(), QuoteStatus.Done, o.price(), o.quantity(), o.price() + 1_000_000L);
        } else if (n % 3 == 1) {
          engine.onQuoteResponse(o.quoteId(), QuoteStatus.TradedAway, 0L, 0L, n % 2 == 0 ? 0L : o.price() + 500_000L);
        }
      }
      engine.poll(now);
    }
  }
}
