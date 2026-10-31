package io.ratesalgo.posttrade.position;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.trade.BookAssignment;
import io.ratesalgo.posttrade.trade.PositionKey;
import io.ratesalgo.posttrade.trade.Trade;
import io.ratesalgo.posttrade.trade.TradeKey;
import io.ratesalgo.posttrade.trade.TradeStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Weighted average cost conserves money: for any sequence of trades, realised P&amp;L plus the
 * unrealised P&amp;L of the remainder at a mark equals the cash flow of the trades plus the value
 * of the remainder at that mark. Tolerance: 1e-6 minor units absolute, which bounds the
 * 34-digit intermediate rounding.
 */
class PositionCalculatorProperties {

  private static final PositionKey KEY = new PositionKey(1, 1, 1001);
  private static final BookAssignment BOOK = new BookAssignment(1, 1, "ALG-TEST");
  private static final InstrumentCatalogue CAT = new InstrumentCatalogue()
      .add(new InstrumentReference(1001, "X", "USD", "PERC", new BigDecimal("8.5")));
  private static final BigDecimal TOLERANCE = new BigDecimal("0.000001");

  @Property(tries = 300)
  void realisedPlusUnrealisedEqualsCashPlusValue(
      @ForAll @Size(min = 1, max = 30) final List<@IntRange(min = -50, max = 50) Integer> qtys,
      @ForAll @Size(min = 30, max = 30) final List<@IntRange(min = 9_000, max = 11_000) Integer> prices,
      @ForAll @IntRange(min = 9_000, max = 11_000) final int mark) {
    final List<Trade> trades = new ArrayList<>();
    BigDecimal cash = BigDecimal.ZERO;
    long net = 0;
    for (int i = 0; i < qtys.size(); i++) {
      final long q = qtys.get(i) * 1_000_000L;
      if (q == 0) {
        continue;
      }
      final long price = prices.get(i) * 10_000_000L;
      trades.add(new Trade(new TradeKey(1, TradeSource.Execution, i), 0, i, 1001,
          q > 0 ? Side.Buy : Side.Sell, price, Math.abs(q), i, i, BOOK, TradeStatus.LIVE, 1, false));
      cash = cash.subtract(BigDecimal.valueOf(q).multiply(BigDecimal.valueOf(price)));
      net += q;
    }
    final Position p = PositionCalculator.compute(KEY, null, trades, CAT);
    assertEquals(net, p.netQuantity());
    final BigDecimal markPx = BigDecimal.valueOf(mark * 10_000_000L);
    final BigDecimal unrealised = CAT.pnl(1001, p.netQuantity(), p.averagePrice(), markPx);
    final BigDecimal expected = cash.add(BigDecimal.valueOf(net).multiply(markPx))
        .multiply(new BigDecimal("8.5")).divide(InstrumentCatalogue.SCALE, InstrumentCatalogue.PRECISION);
    final BigDecimal diff = p.realisedPnl().add(unrealised).subtract(expected).abs();
    assertTrue(diff.compareTo(TOLERANCE) <= 0, () -> "difference " + diff);
  }

  @Property(tries = 200)
  void flatPositionHasNoCost(
      @ForAll @Size(min = 1, max = 20) final List<@IntRange(min = 1, max = 50) Integer> qtys,
      @ForAll @IntRange(min = 9_000, max = 11_000) final int price) {
    final List<Trade> trades = new ArrayList<>();
    long total = 0;
    for (int i = 0; i < qtys.size(); i++) {
      total += qtys.get(i);
      trades.add(new Trade(new TradeKey(1, TradeSource.Execution, i), 0, i, 1001, Side.Buy,
          price * 10_000_000L, qtys.get(i), i, i, BOOK, TradeStatus.LIVE, 1, false));
    }
    trades.add(new Trade(new TradeKey(1, TradeSource.Execution, 999), 0, 999, 1001, Side.Sell,
        price * 10_000_000L, total, 999, 999, BOOK, TradeStatus.LIVE, 1, false));
    final Position p = PositionCalculator.compute(KEY, null, trades, CAT);
    assertEquals(0, p.netQuantity());
    assertEquals(0, p.averagePrice().signum());
    assertEquals(0, p.realisedMinor());
  }
}
