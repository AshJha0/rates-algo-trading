package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CountingOutboundSink;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * P3 gate: the per-RFQ path (request, risk check, quote, response, fill booking, poll with expiry
 * and markouts) allocates nothing once warmed up, measured with the JVM's per-thread allocation
 * counter over five windows of 20,000 RFQs. At least one window must be exactly zero and none may
 * reach 4 KB. A few hundred bytes can appear in a window when the JIT deoptimises and recompiles;
 * the same loop run with -Xint and a JFR recording of every allocation (TLABs off) shows no
 * allocation from the path (DR-P3-08).
 */
class QuotingAllocationTest {

  private static final int WARM = 100_000;
  private static final int MEASURED = 20_000;
  private static final int WINDOWS = 5;

  private long rfqId;

  @Test
  void perRfqPathAllocatesNothingAfterWarmUp() {
    final CountingOutboundSink gateway = new CountingOutboundSink();
    final TestMarket m =
        new TestMarket(TestMarket.standardLimits().defaultMaxPosition(1_000_000 * FACE_1M).bucketPv01(1e12).netPv01(1e12).grossPv01(1e12),
            new QuotingConfig(), Hedger.config(), gateway, null);
    final com.sun.management.ThreadMXBean mx =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    assertTrue(mx.isThreadAllocatedMemorySupported());
    m.control.keep(false); // the recording stub copies each alert; a real control ring does not
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
        "QuotingAllocationTest: " + WINDOWS + " windows of " + MEASURED + " RFQs, " + quoted + " quotes, bytes per window [" + windows + "]");
    assertTrue(quoted > WINDOWS * MEASURED / 2, "quoted " + quoted);
    // One allocation of the smallest object (16 bytes) per RFQ would add 320,000 bytes per window.
    assertEquals(0L, min, "a steady-state window allocates nothing");
    assertTrue(max < 4_096L, "no window allocates per RFQ: max " + max);
  }

  private void run(final TestMarket m, final int count) {
    final QuotingEngine engine = m.desk.quoting();
    for (int n = 0; n < count; n++) {
      m.clock.advance(2_000_000L);
      final long now = m.clock.epochNanos();
      if (n % 50 == 0) {
        m.refreshAll(now);
      }
      final int id = TestMarket.RFQ_INSTRUMENTS[n % TestMarket.RFQ_INSTRUMENTS.length];
      final Side side = (n / 10) % 2 == 0 ? Side.Buy : Side.Sell;
      final int client = 5 + (n / 20) % 10;
      final RfqDecision d = engine.onQuoteRequest(7, ++rfqId, client, id, side, (1 + n % 50) * FACE_1M, 4, 0L, rfqId);
      if (d != RfqDecision.PASS) {
        final RfqOutcome o = engine.outcome();
        if (n % 3 == 0) {
          engine.onQuoteResponse(o.quoteId(), QuoteStatus.Done, o.price(), o.quantity(), o.price() + 1_000_000L);
        } else if (n % 3 == 1) {
          engine.onQuoteResponse(o.quoteId(), QuoteStatus.TradedAway, 0L, 0L, 0L);
        }
      }
      engine.poll(now);
    }
  }
}
