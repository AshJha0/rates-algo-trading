package io.ratesalgo.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.Side;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cancels and touch reads of the strategy context (DR-V1H-01). */
class OrderCancelTest {

  @TempDir Path dir;

  /** Rests one bid at the touch at the first two-sided future book, cancels it on a timer, and records reports. */
  private static final class Rester implements Strategy {
    final List<OrdStatus> statuses = new ArrayList<>();
    long bid;
    long bidQty;
    long orderId = -1;
    boolean cancelled;
    boolean secondCancel = true;

    @Override
    public String name() {
      return "rester";
    }

    @Override
    public void onQuoteRequest(final QuoteRequestDecoder rfq, final StrategyContext ctx) {}

    @Override
    public void onBookUpdate(final BookUpdateDecoder update, final StrategyContext ctx) {
      final int ty = InstrumentSet.TY_FUTURE;
      if (orderId < 0 && ctx.touchPrice(ty, true) != 0 && ctx.touchPrice(ty, false) != 0) {
        bid = ctx.touchPrice(ty, true);
        bidQty = ctx.touchQuantity(ty, true);
        // join the bid queue behind its displayed quantity (an order below the best fills at once in
        // the queue model, which sees no queue there)
        orderId = ctx.sendOrder(ty, Side.Buy, OrdType.Limit, 10 * ctx.instruments().get(ty).lotSize(), bid, true);
        ctx.scheduleTimer(ctx.nowEpochNanos() + 10_000_000L);
      }
    }

    @Override
    public void onTimer(final long timerId, final long nowEpochNanos, final StrategyContext ctx) {
      cancelled = ctx.cancelOrder(orderId);
      secondCancel = ctx.cancelOrder(orderId);
    }

    @Override
    public void onExecutionReport(final ExecutionReportDecoder report, final StrategyContext ctx) {
      statuses.add(report.ordStatus());
    }
  }

  @Test
  void aRestingLimitOrderIsCancelledOnceAndNeverFills() {
    final Path journal = TestSupport.day(dir, LocalDate.of(2026, 1, 6));
    final Rester s = new Rester();
    final BacktestResult r = new BacktestRunner(TestSupport.config(journal, BandPoint.CENTRAL)).run(s);
    assertTrue(s.bid > 0 && s.bidQty > 0, "the future book is visible through the touch reads");
    assertTrue(s.cancelled);
    assertFalse(s.secondCancel, "a cancelled order cannot be cancelled again");
    assertEquals(List.of(OrdStatus.New, OrdStatus.Cancelled), s.statuses);
    assertEquals(0, r.hedgeFills());
  }
}
