package io.ratesalgo.strategy;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.EventCalendar;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseEncoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.strategy.experiment.DeskFactory;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * The desk strategy's per-RFQ and per-fill paths allocate nothing once warmed up, with every
 * feature on: ladder refresh, sizing, funding penalty, tier lookup, spread optimisation, axes,
 * quote, fill bookkeeping, flow estimate, markout scheduling and recording, hedge budget, timing
 * decision, hedge order and timers. Measured with {@code ThreadMXBean.getThreadAllocatedBytes} on
 * the calling thread, against a stub context that itself allocates nothing. The tiering's table
 * review (which builds a new table version) runs at the review cadence, not per fill, and is kept
 * out of the window by a minimum sample the test never reaches.
 */
class DeskQuoterAllocationTest {

  private static final com.sun.management.ThreadMXBean BEAN =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

  private static final long MM = InstrumentSet.MILLION;
  private static final int[] PRODUCTS = {
    InstrumentSet.SOFR_2Y, InstrumentSet.SOFR_10Y, InstrumentSet.SOFR_30Y, InstrumentSet.UST_10Y, InstrumentSet.SOFR_5Y
  };

  /** A context with fixed mids, positions moved by the test, and counters instead of output. */
  private static final class Stub implements StrategyContext {
    final InstrumentSet set = InstrumentSet.usdDefault();
    final double[] mids = new double[400];
    final long[] positions = new long[400];
    final long[] timerAt = new long[1 << 16];
    final EventCalendar calendar = new EventCalendar(new long[] {5_000_000_000_000L}, 900_000_000_000L);
    long now = 1_000_000_000_000L;
    long quotes;
    long orders;
    long timers;
    long firedTimers;
    long lastOrderId;
    Side lastOrderSide;
    long lastOrderQty;

    Stub() {
      for (final int id : new int[] {101, 102, 103, 104}) {
        mids[id] = 4.0;
      }
      mids[201] = 100.0;
      mids[202] = 100.0;
      mids[301] = 112.0;
    }

    @Override
    public long nowEpochNanos() {
      return now;
    }

    @Override
    public InstrumentSet instruments() {
      return set;
    }

    @Override
    public boolean hasMid(final int instrumentId) {
      return mids[instrumentId] > 0;
    }

    @Override
    public double mid(final int instrumentId) {
      return mids[instrumentId];
    }

    @Override
    public double bookHalfSpread(final int instrumentId) {
      return 0.0;
    }

    @Override
    public long position(final int instrumentId) {
      return positions[instrumentId];
    }

    @Override
    public double netDv01() {
      return 0.0;
    }

    @Override
    public HitRateModel hitRateModel() {
      return null;
    }

    @Override
    public EventCalendar calendar() {
      return calendar;
    }

    @Override
    public void quote(final long rfqId, final long bidMantissa, final long askMantissa) {
      quotes++;
    }

    @Override
    public long sendOrder(
        final int instrumentId, final Side side, final OrdType type, final long quantity, final long price,
        final boolean hedge) {
      orders++;
      lastOrderId = orders;
      lastOrderSide = side;
      lastOrderQty = quantity;
      positions[instrumentId] += side == Side.Buy ? quantity : -quantity;
      return orders;
    }

    @Override
    public long scheduleTimer(final long epochNanos) {
      timers++;
      timerAt[(int) (timers & (timerAt.length - 1))] = epochNanos;
      return timers;
    }
  }

  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
  private final MessageHeaderEncoder headerEnc = new MessageHeaderEncoder();
  private final MessageHeaderDecoder headerDec = new MessageHeaderDecoder();
  private final QuoteRequestEncoder rfqEnc = new QuoteRequestEncoder();
  private final QuoteRequestDecoder rfqDec = new QuoteRequestDecoder();
  private final QuoteResponseEncoder respEnc = new QuoteResponseEncoder();
  private final QuoteResponseDecoder respDec = new QuoteResponseDecoder();
  private final ExecutionReportEncoder execEnc = new ExecutionReportEncoder();
  private final ExecutionReportDecoder execDec = new ExecutionReportDecoder();
  private long nextTimerToFire = 1;

  private void runRfqs(final DeskQuoter q, final Stub ctx, final long first, final int count) {
    for (long id = first; id < first + count; id++) {
      ctx.now += 2_000_000_000L;
      final int product = PRODUCTS[(int) (id % PRODUCTS.length)];
      final boolean clientBuys = (id / 3) % 2 == 0;
      final long qty = (5 + id % 60) * MM;
      rfqEnc
          .wrapAndApplyHeader(buffer, 0, headerEnc)
          .venueId(1)
          .rfqId(id)
          .clientId((int) (1 + id % 12))
          .instrumentId(product)
          .side(clientBuys ? Side.Buy : Side.Sell)
          .quantity(qty)
          .dealerCount((short) (2 + id % 4))
          .receivedNanos(ctx.now)
          .expiryNanos(ctx.now + 30_000_000_000L);
      rfqDec.wrapAndApplyHeader(buffer, 0, headerDec);
      final long quotesBefore = ctx.quotes;
      q.onQuoteRequest(rfqDec, ctx);
      if (ctx.quotes == quotesBefore) {
        continue;
      }
      final boolean won = id % 2 == 0;
      if (won) {
        ctx.positions[product] += clientBuys ? -qty : qty;
      }
      respEnc
          .wrapAndApplyHeader(buffer, 0, headerEnc)
          .venueId(1)
          .rfqId(id)
          .quoteId(id)
          .status(won ? QuoteStatus.Done : QuoteStatus.TradedAway)
          .tradedQuantity(qty)
          .timestampNanos(ctx.now);
      respDec.wrapAndApplyHeader(buffer, 0, headerDec);
      final long ordersBefore = ctx.orders;
      q.onQuoteResponse(respDec, ctx);
      if (ctx.orders != ordersBefore) {
        execEnc
            .wrapAndApplyHeader(buffer, 0, headerEnc)
            .venueId(2)
            .clOrdId(ctx.lastOrderId)
            .instrumentId(InstrumentSet.TY_FUTURE)
            .side(ctx.lastOrderSide)
            .ordStatus(OrdStatus.Filled)
            .lastQuantity(ctx.lastOrderQty)
            .cumQuantity(ctx.lastOrderQty)
            .transactNanos(ctx.now);
        execDec.wrapAndApplyHeader(buffer, 0, headerDec);
        q.onExecutionReport(execDec, ctx);
      }
      while (nextTimerToFire <= ctx.timers
          && ctx.timerAt[(int) (nextTimerToFire & (ctx.timerAt.length - 1))] <= ctx.now) {
        q.onTimer(nextTimerToFire++, ctx.now, ctx);
        ctx.firedTimers++;
      }
    }
  }

  @Test
  void perRfqAndPerFillPathsAreAllocationFreeAfterWarmUp() {
    final DeskFactory f = Support.factory(BandPoint.CENTRAL);
    final List<ClientTier> tiers =
        List.of(
            new ClientTier(1, 1.0, Long.MAX_VALUE, true, false),
            new ClientTier(2, 1.0, Long.MAX_VALUE, true, false),
            new ClientTier(3, 1.0, Long.MAX_VALUE, true, false));
    final MarkoutTiering tiering =
        new MarkoutTiering(
            tiers, new double[] {-0.045, -0.098, -1e9}, new double[] {1e9, -0.035, -0.088}, Long.MAX_VALUE, 0.05, 1,
            0, 9001, 9002, 64);
    final DeskQuoter q = f.treatment(DeskFactory.DESK_ALL, tiering);
    final Stub ctx = new Stub();
    q.onStart(ctx);
    // Warm-up and measurement in one loop, as RouterAllocationTest does: at least 50 windows of
    // 200 RFQs, and then until 20 windows in a row (4,000 RFQs with their fills, hedges and
    // timers) allocate nothing. Measured on 2026-10-31 (JDK 25.0.5, Windows 11): the strategy
    // settles to zero within the first windows; a separate measurement loop after the warm-up saw
    // one 168-byte window at its first iteration (on-stack replacement of the new loop) and
    // zero in the 39 after it, so the measurement stays inside the warmed-up loop.
    long id = 1;
    int quiet = 0;
    int windows = 0;
    while (quiet < 20 || windows < 50) {
      if (++windows > 300) {
        fail("the per-RFQ path did not allocate nothing for 20 windows in a row within 300 windows");
      }
      final long before = BEAN.getCurrentThreadAllocatedBytes();
      runRfqs(q, ctx, id, 200);
      final long bytes = BEAN.getCurrentThreadAllocatedBytes() - before;
      id += 200;
      quiet = bytes == 0 ? quiet + 1 : 0;
    }
    // the loop exits with at least 20 quiet windows in a row; more when the path settled early,
    // as on the Linux CI runner where all 50 were quiet (DR-INT-08)
    assertTrue(quiet >= 20, "quiet windows at the end " + quiet);
    assertTrue(ctx.quotes > 1_000, "quotes " + ctx.quotes);
    assertTrue(q.clientFills() > 500, "fills " + q.clientFills());
    assertTrue(q.markoutsRecorded() > 100, "markouts " + q.markoutsRecorded());
    assertTrue(ctx.firedTimers > 100, "timers " + ctx.firedTimers);
    assertTrue(q.hedgesSent() + q.waits() > 0, "hedge decisions");
  }
}
