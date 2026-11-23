package io.ratesalgo.posttrade.position;

import static io.ratesalgo.posttrade.Fixtures.NINE;
import static io.ratesalgo.posttrade.Fixtures.px;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.AmendReason;
import io.ratesalgo.messages.sbe.BustReason;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.Fixtures;
import io.ratesalgo.posttrade.chaos.JournalScript;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import io.ratesalgo.posttrade.records.OrderRecord;
import io.ratesalgo.posttrade.trade.PositionKey;
import io.ratesalgo.posttrade.trade.TradeKey;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The order records and audit entries the keeper derives from each journal event, field by field
 * (API_SPEC.md Sections 17.2 and 17.4), and the edges of what counts as a fill, a late fill and an
 * open order.
 */
class PositionKeeperRecordsTest {

  @TempDir Path dir;

  private static final PositionKey BOND = new PositionKey(1, 10, 1001);

  private PositionKeeper replay(final Consumer<JournalScript> script) {
    final Path j = dir.resolve("j" + System.nanoTime() + ".raj");
    try (JournalScript s = new JournalScript(j, 1 << 16, SyntheticDay.MIDNIGHT)) {
      script.accept(s);
    }
    final PositionKeeper keeper = new PositionKeeper(Fixtures.books(), Fixtures.catalogue());
    new Replayer(new ManualClock(0)).replay(j, keeper);
    return keeper;
  }

  @Test
  void everyEventLeavesARecordWithItsOwnFields() {
    final PositionKeeper k = replay(s -> {
      s.newOrder(NINE, 1, 7, 1001, Side.Buy, px("100"), 300);
      s.executionReport(NINE + 2, 1, 7, 507, 71, 1001, Side.Buy, OrdStatus.PartiallyFilled, px("100.5"), 100,
          200, 100, NINE + 2);
      s.quoteRequest(NINE + 5, 2, 61, 301, 2001, Side.Buy, 1_000, "USD");
      s.quote(NINE + 6, 2, 61, 81, 2001, px("4.25"), px("4.26"), 1_000, NINE + 100);
      s.quoteResponse(NINE + 7, 2, 61, 81, QuoteStatus.Done, px("4.26"), 1_000, NINE + 7);
      s.bust(NINE + 8, 1, TradeSource.Execution, 71, 507, 1001, BustReason.VenueError);
      s.amend(NINE + 9, 2, TradeSource.Rfq, 81, px("4.27"), 900, 42, AmendReason.PriceCorrection);
    });
    final List<OrderRecord> r = k.orderRecords();
    assertEquals(8, r.size());
    for (int i = 0; i < r.size(); i++) {
      assertEquals(i + 1L, r.get(i).recordSequence(), "record sequence is 1, 2, 3, ...");
      assertEquals("2026-10-30", r.get(i).tradingDate());
      assertEquals("DEAL", r.get(i).tradingCapacity());
    }
    final OrderRecord order = r.get(0);
    assertEquals(List.of("T3", "NEWO", "BUYI", "LMT", "VENUE-1", "GDAY", ""),
        List.of(order.table(), order.eventType(), order.buySell(), order.orderType(), order.orderReceiver(),
            order.validityPeriod(), order.tradeKey()));
    assertEquals(px("100"), order.limitPrice());
    assertEquals(300, order.remainingQuantity());
    assertEquals(0, order.tradedQuantity());
    assertEquals(7, order.internalOrderId());
    assertEquals(NINE, order.submissionNanos());
    assertFalse(order.liquidityProvision());

    final OrderRecord fill = r.get(1);
    assertEquals(List.of("PARF", "BUYI", "71", "1:E:71"),
        List.of(fill.eventType(), fill.buySell(), fill.venueTransactionId(), fill.tradeKey()));
    assertEquals(px("100.5"), fill.limitPrice());
    assertEquals(200, fill.remainingQuantity());
    assertEquals(100, fill.tradedQuantity());
    assertEquals(507, fill.venueOrderId());
    assertEquals(NINE + 2, fill.receiptNanos());

    final OrderRecord rfq = r.get(2);
    assertEquals(List.of("T2", "NEWO", "BUYI", "RFQ", "VENUE-2", "GTDT"),
        List.of(rfq.table(), rfq.eventType(), rfq.buySell(), rfq.orderType(), rfq.orderReceiver(), rfq.validityPeriod()));
    assertTrue(rfq.liquidityProvision());
    assertEquals(61, rfq.internalOrderId());
    assertEquals(301, rfq.clientId());
    assertEquals(1_000, rfq.remainingQuantity());

    final OrderRecord bid = r.get(3);
    final OrderRecord ask = r.get(4);
    assertEquals(List.of("BUYI", "SELL"), List.of(bid.buySell(), ask.buySell()), "a two-way quote is two records");
    assertEquals(px("4.25"), bid.limitPrice());
    assertEquals(px("4.26"), ask.limitPrice());
    assertEquals(1_000, bid.remainingQuantity());
    assertEquals(1_000, ask.remainingQuantity());
    assertEquals("QUOTE", bid.orderType());
    assertTrue(bid.liquidityProvision());

    final OrderRecord done = r.get(5);
    assertEquals(List.of("FILL", "SELL", "QUOTE", "2:R:81", "2:R:81"),
        List.of(done.eventType(), done.buySell(), done.orderType(), done.venueTransactionId(), done.tradeKey()),
        "the client bought, so we sold");
    assertEquals(px("4.26"), done.limitPrice());
    assertEquals(1_000, done.tradedQuantity());
    assertEquals(301, done.clientId());
    assertTrue(done.liquidityProvision());

    final OrderRecord bust = r.get(6);
    assertEquals(List.of("BUST", "BUYI", "1:E:71"), List.of(bust.eventType(), bust.buySell(), bust.tradeKey()));
    assertFalse(bust.liquidityProvision(), "an order's fill");
    assertEquals(100, bust.tradedQuantity());
    assertEquals(px("100.5"), bust.limitPrice());

    final OrderRecord amend = r.get(7);
    assertEquals(List.of("AMND", "SELL", "2:R:81"), List.of(amend.eventType(), amend.buySell(), amend.tradeKey()));
    assertTrue(amend.liquidityProvision(), "an RFQ trade");
    assertEquals(px("4.27"), amend.limitPrice());
    assertEquals(900, amend.tradedQuantity());
    assertEquals(NINE + 9, k.lastTimestampNanos());
  }

  @Test
  void anRfqTradeBustIsRecordedAsLiquidityProvision() {
    final PositionKeeper k = replay(s -> {
      s.quoteRequest(NINE, 2, 61, 301, 2001, Side.Sell, 1_000, "USD");
      s.quoteResponse(NINE + 1, 2, 61, 81, QuoteStatus.Done, px("4.25"), 1_000, NINE + 1);
      s.bust(NINE + 2, 2, TradeSource.Rfq, 81, 0, 2001, BustReason.VenueError);
    });
    final OrderRecord bust = k.orderRecords().get(k.orderRecords().size() - 1);
    assertEquals("BUST", bust.eventType());
    assertEquals("BUYI", bust.buySell(), "the client sold, so we bought");
    assertTrue(bust.liquidityProvision());
  }

  @Test
  void reportsAndOutcomesWithoutQuantityAreNotTrades() {
    final PositionKeeper k = replay(s -> {
      s.executionReport(NINE, 1, 7, 507, 71, 1001, Side.Buy, OrdStatus.PartiallyFilled, px("100"), 0, 300, 0, NINE);
      s.quoteRequest(NINE + 1, 2, 61, 301, 2001, Side.Buy, 1_000, "USD");
      s.quoteResponse(NINE + 2, 2, 61, 81, QuoteStatus.Done, px("4.25"), 0, NINE + 2);
      s.quoteRequest(NINE + 3, 2, 62, 301, 2001, Side.Buy, 1_000, "USD");
      s.quoteResponse(NINE + 4, 2, 62, 82, QuoteStatus.Expired, 0, 0, NINE + 4);
    });
    assertTrue(k.trades().isEmpty());
    assertEquals(List.of("PARF", "NEWO", "REMO", "NEWO", "EXPI"),
        k.orderRecords().stream().map(OrderRecord::eventType).toList());
    assertEquals(0, k.orderRecords().get(0).limitPrice(), "no fill, no price");
    assertEquals("", k.orderRecords().get(2).tradeKey());
  }

  @Test
  void repeatsAreIgnoredOrFlaggedWithWhatDiffered() {
    final PositionKeeper k = replay(s -> {
      s.executionReport(NINE, 1, 7, 507, 71, 1001, Side.Buy, OrdStatus.Filled, px("100"), 100, 0, 100, NINE);
      s.executionReport(NINE + 1, 1, 7, 507, 71, 1001, Side.Buy, OrdStatus.Filled, px("100"), 100, 0, 100, NINE);
      s.executionReport(NINE + 2, 1, 7, 507, 71, 1001, Side.Buy, OrdStatus.Filled, px("101"), 100, 0, 100, NINE);
      s.quoteRequest(NINE + 3, 2, 61, 301, 2001, Side.Buy, 1_000, "USD");
      s.quoteResponse(NINE + 4, 2, 61, 81, QuoteStatus.Done, px("4.25"), 1_000, NINE + 4);
      s.quoteResponse(NINE + 5, 2, 61, 81, QuoteStatus.Done, px("4.25"), 1_000, NINE + 4);
      s.quoteResponse(NINE + 6, 2, 61, 81, QuoteStatus.Done, px("4.25"), 999, NINE + 4);
    });
    final List<AuditEntry> ignored = k.audit(AuditKind.DUPLICATE_IGNORED);
    final List<AuditEntry> conflict = k.audit(AuditKind.DUPLICATE_CONFLICT);
    assertEquals(2, ignored.size());
    assertEquals(2, conflict.size());
    assertEquals("repeat of execution report", ignored.get(0).detail());
    assertEquals("repeat of quote response", ignored.get(1).detail());
    assertEquals("conflicting repeat: price=" + px("101") + " qty=100", conflict.get(0).detail());
    assertEquals("conflicting repeat: price=" + px("4.25") + " qty=999", conflict.get(1).detail());
    assertEquals(2, k.trades().size());
  }

  @Test
  void aFillAtTheSameTransactionTimeAsTheLatestIsNotLate() {
    final PositionKeeper k = replay(s -> {
      s.executionReport(NINE, 1, 7, 507, 71, 1001, Side.Buy, OrdStatus.Filled, px("100"), 100, 0, 100, NINE + 5);
      s.executionReport(NINE + 1, 1, 8, 508, 72, 1001, Side.Buy, OrdStatus.Filled, px("100"), 100, 0, 100, NINE + 5);
      s.executionReport(NINE + 2, 1, 9, 509, 73, 1001, Side.Buy, OrdStatus.Filled, px("100"), 100, 0, 100, NINE + 4);
    });
    assertEquals(1, k.audit(AuditKind.LATE_FILL).size());
    assertEquals(new TradeKey(1, TradeSource.Execution, 73), k.audit(AuditKind.LATE_FILL).get(0).key());
    assertTrue(k.trade(new TradeKey(1, TradeSource.Execution, 73)).late());
    assertFalse(k.trade(new TradeKey(1, TradeSource.Execution, 72)).late());
    assertEquals(300, k.position(BOND).netQuantity());
  }

  @Test
  void anOrderWithNothingLeftIsNotOpenWhateverItsStatus() {
    final PositionKeeper k = replay(s -> {
      s.executionReport(NINE, 1, 7, 507, 70, 1001, Side.Buy, OrdStatus.New, 0, 0, 0, 0, NINE);
      s.executionReport(NINE + 1, 1, 8, 508, 80, 1001, Side.Buy, OrdStatus.New, 0, 0, 1, 0, NINE + 1);
    });
    assertEquals(List.of(new OpenOrder(1, 8, 508, 1001, 1, 0)), k.openOrders());
  }

  @Test
  void liveQuotesAreListedByVenueThenQuoteId() {
    final PositionKeeper k = replay(s -> {
      for (long q = 90; q >= 81; q--) {
        s.quote(NINE, (int) (2 + q % 3), 61, q, 2001, px("4.25"), px("4.26"), 1_000, NINE + 100);
      }
    });
    final List<LiveQuote> live = k.liveQuotes(NINE);
    assertEquals(10, live.size());
    for (int i = 1; i < live.size(); i++) {
      final LiveQuote a = live.get(i - 1);
      final LiveQuote b = live.get(i);
      assertTrue(a.venueId() < b.venueId() || (a.venueId() == b.venueId() && a.quoteId() < b.quoteId()), live.toString());
    }
  }

  @Test
  void aHeldVenueBustForOneExecutionDoesNotDropAnotherHeldForAnRfqTrade() {
    final PositionKeeper k = replay(s -> {
      // two venue busts before anything is booked: each may be for an execution or an RFQ trade
      s.venueBust(NINE, 2, 81, 1, 2001, Side.Sell, px("4.25"), 1_000);
      s.venueBust(NINE + 1, 2, 82, 2, 2001, Side.Sell, px("4.25"), 1_000);
      // the first was for an execution after all
      s.executionReport(NINE + 2, 2, 81, 581, 1, 2001, Side.Sell, OrdStatus.Filled, px("4.25"), 1_000, 0, 1_000, NINE + 2);
      // the second is for the RFQ trade keyed by our quote id 82
      s.quoteRequest(NINE + 3, 2, 62, 301, 2001, Side.Buy, 1_000, "USD");
      s.quoteResponse(NINE + 4, 2, 62, 82, QuoteStatus.Done, px("4.25"), 1_000, NINE + 4);
    });
    assertFalse(k.trade(new TradeKey(2, TradeSource.Execution, 1)).isLive());
    assertFalse(k.trade(new TradeKey(2, TradeSource.Rfq, 82)).isLive(), "the held bust still found its RFQ trade");
    assertTrue(k.pendingBusts().isEmpty());
  }

  @Test
  void eachSideOfATwoWayQuoteIsRecordedWithItsOwnQuantity() {
    final byte[] bytes = new byte[256];
    final UnsafeBuffer b = new UnsafeBuffer(bytes);
    final QuoteEncoder q = new QuoteEncoder();
    q.wrapAndApplyHeader(b, 0, new MessageHeaderEncoder()).venueId(2).rfqId(61).quoteId(81).instrumentId(2001)
        .bidQuantity(700).askQuantity(300).referenceNanos(NINE).validUntilNanos(NINE + 100).sentNanos(NINE);
    q.bidPrice().mantissa(px("4.25"));
    q.askPrice().mantissa(px("4.26"));
    final int length = MessageHeaderEncoder.ENCODED_LENGTH + q.encodedLength();
    final PositionKeeper k = replay(s -> s.raw(NINE, QuoteEncoder.TEMPLATE_ID, Arrays.copyOf(bytes, length)));
    assertEquals(List.of(700L, 300L), k.orderRecords().stream().map(OrderRecord::remainingQuantity).toList());
  }
}
