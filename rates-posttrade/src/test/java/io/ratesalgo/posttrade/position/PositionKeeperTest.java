package io.ratesalgo.posttrade.position;

import static io.ratesalgo.posttrade.Fixtures.NINE;
import static io.ratesalgo.posttrade.Fixtures.px;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.AmendReason;
import io.ratesalgo.messages.sbe.BustReason;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.Fixtures;
import io.ratesalgo.posttrade.chaos.JournalScript;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import io.ratesalgo.posttrade.records.OrderRecord;
import io.ratesalgo.posttrade.trade.PositionKey;
import io.ratesalgo.posttrade.trade.Trade;
import io.ratesalgo.posttrade.trade.TradeKey;
import io.ratesalgo.posttrade.trade.TradeStatus;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PositionKeeperTest {

  @TempDir Path dir;

  private static final PositionKey BOND = new PositionKey(1, 10, 1001);
  private static final TradeKey E1 = new TradeKey(1, TradeSource.Execution, 1);
  private static final TradeKey E2 = new TradeKey(1, TradeSource.Execution, 2);

  private PositionKeeper replay(final Consumer<JournalScript> script) {
    final Path j = dir.resolve("j" + System.nanoTime() + ".raj");
    try (JournalScript s = new JournalScript(j, 1 << 16, SyntheticDay.MIDNIGHT)) {
      script.accept(s);
    }
    final PositionKeeper keeper = new PositionKeeper(Fixtures.books(), Fixtures.catalogue());
    new Replayer(new ManualClock(0)).replay(j, keeper);
    return keeper;
  }

  private static void fill(final JournalScript s, final long at, final long execId, final Side side,
      final String price, final long qty, final long transact) {
    s.executionReport(at, 1, execId, 500 + execId, execId, 1001, side, OrdStatus.Filled, px(price), qty, 0, qty,
        transact);
  }

  @Test
  void duplicateFillIsIgnoredOnce() {
    final PositionKeeper k = replay(s -> {
      fill(s, NINE, 1, Side.Buy, "100", 100, NINE);
      fill(s, NINE + 1, 1, Side.Buy, "100", 100, NINE);
    });
    assertEquals(100, k.position(BOND).netQuantity());
    assertEquals(1, k.audit(AuditKind.DUPLICATE_IGNORED).size());
    assertTrue(k.audit(AuditKind.DUPLICATE_CONFLICT).isEmpty());
  }

  @Test
  void conflictingDuplicateKeepsTheFirstAndIsFlagged() {
    final PositionKeeper k = replay(s -> {
      fill(s, NINE, 1, Side.Buy, "100", 100, NINE);
      fill(s, NINE + 1, 1, Side.Buy, "101", 100, NINE);
    });
    assertEquals(px("100"), k.trade(E1).price());
    assertEquals(1, k.audit(AuditKind.DUPLICATE_CONFLICT).size());
  }

  @Test
  void lateFillLandsInTransactionOrder() {
    // sell 100 at 101 first in time, buy 100 at 100 later in time but arriving first
    final PositionKeeper inOrder = replay(s -> {
      fill(s, NINE, 2, Side.Buy, "100", 100, NINE);
      fill(s, NINE + 10, 1, Side.Sell, "101", 100, NINE + 5);
    });
    final PositionKeeper outOfOrder = replay(s -> {
      fill(s, NINE, 2, Side.Buy, "100", 100, NINE + 10);
      fill(s, NINE + 20, 1, Side.Sell, "101", 100, NINE + 5);
    });
    assertEquals(0, outOfOrder.position(BOND).netQuantity());
    assertTrue(outOfOrder.trade(E1).late());
    assertFalse(outOfOrder.trade(E2).late());
    assertEquals(1, outOfOrder.audit(AuditKind.LATE_FILL).size());
    assertTrue(inOrder.audit(AuditKind.LATE_FILL).isEmpty());
    // short 100 at 101 then bought back at 100: realised 100 * 1 / 100 = 1 minor unit
    assertEquals(1, outOfOrder.position(BOND).realisedMinor());
  }

  @Test
  void bustRemovesTheTradeAndKeepsTheRecord() {
    final PositionKeeper k = replay(s -> {
      fill(s, NINE, 1, Side.Buy, "100", 100, NINE);
      s.bust(NINE + 5, 1, TradeSource.Execution, 1, 501, 1001, BustReason.VenueError);
      s.bust(NINE + 6, 1, TradeSource.Execution, 1, 501, 1001, BustReason.VenueError);
    });
    assertEquals(0, k.position(BOND).netQuantity());
    assertEquals(TradeStatus.BUSTED, k.trade(E1).status());
    assertEquals(1, k.audit(AuditKind.BUSTED).size());
    assertEquals(1, k.audit(AuditKind.DUPLICATE_BUST).size());
    final List<OrderRecord> records = k.recordsByVenueOrderId(501);
    assertEquals(List.of("FILL", "BUST"), records.stream().map(OrderRecord::eventType).toList());
  }

  @Test
  void venueTradeBustIsAppliedAsAVenueErrorBust() {
    final PositionKeeper k = replay(s -> {
      fill(s, NINE, 1, Side.Buy, "100", 100, NINE);
      s.venueBust(NINE + 5, 1, 1, 1, 1001, Side.Buy, px("100"), 100);
      s.venueBust(NINE + 6, 1, 1, 7, 1001, Side.Buy, px("100"), 100);
    });
    assertEquals(0, k.position(BOND).netQuantity());
    assertEquals(TradeStatus.BUSTED, k.trade(E1).status());
    assertEquals("reason=VenueError", k.audit(AuditKind.BUSTED).get(0).detail());
    assertEquals(1, k.pendingBusts().size(), "a bust of an unknown execution waits for its fill");
  }

  @Test
  void bustBeforeFillIsHeldAndApplied() {
    final PositionKeeper k = replay(s -> {
      s.bust(NINE, 1, TradeSource.Execution, 1, 501, 1001, BustReason.VenueError);
      s.bust(NINE + 1, 1, TradeSource.Execution, 1, 501, 1001, BustReason.VenueError);
      assertEquals(2L, s.nextSequence() - 1);
    });
    assertEquals(1, k.pendingBusts().size());
    final PositionKeeper k2 = replay(s -> {
      s.bust(NINE, 1, TradeSource.Execution, 1, 501, 1001, BustReason.VenueError);
      fill(s, NINE + 5, 1, Side.Buy, "100", 100, NINE);
    });
    assertTrue(k2.pendingBusts().isEmpty());
    assertEquals(TradeStatus.BUSTED, k2.trade(E1).status());
    assertEquals(0, k2.position(BOND).netQuantity());
    assertEquals(1, k2.audit(AuditKind.BUST_PENDING).size());
  }

  @Test
  void amendmentIsVersionedAndAudited() {
    final PositionKeeper k = replay(s -> {
      fill(s, NINE, 1, Side.Buy, "100", 100, NINE);
      s.amend(NINE + 5, 1, TradeSource.Execution, 1, px("99"), 200, 42, AmendReason.QuantityCorrection);
      s.amend(NINE + 6, 1, TradeSource.Execution, 99, px("99"), 200, 42, AmendReason.PriceCorrection);
      s.amend(NINE + 7, 1, TradeSource.Execution, 1, px("99"), 0, 42, AmendReason.PriceCorrection);
    });
    final List<Trade> versions = k.versions(E1);
    assertEquals(2, versions.size());
    assertEquals(2, k.trade(E1).version());
    assertEquals(200, k.position(BOND).netQuantity());
    final AuditEntry a = k.audit(AuditKind.AMENDED).get(0);
    assertEquals(42, a.actor());
    assertTrue(a.detail().contains("qty 100->200"), a.detail());
    assertEquals(2, k.audit(AuditKind.AMEND_REJECTED).size());
  }

  @Test
  void amendOfBustedTradeIsRejected() {
    final PositionKeeper k = replay(s -> {
      fill(s, NINE, 1, Side.Buy, "100", 100, NINE);
      s.bust(NINE + 1, 1, TradeSource.Execution, 1, 501, 1001, BustReason.Manual);
      s.amend(NINE + 2, 1, TradeSource.Execution, 1, px("99"), 100, 42, AmendReason.PriceCorrection);
    });
    assertTrue(k.audit(AuditKind.AMEND_REJECTED).get(0).detail().contains("busted"));
  }

  @Test
  void rfqTradeTakesTheOppositeOfTheClientSide() {
    final PositionKeeper k = replay(s -> {
      s.quoteRequest(NINE, 2, 61, 301, 2001, Side.Buy, 1_000, "USD");
      s.quote(NINE + 1, 2, 61, 71, 2001, px("4.25"), px("4.26"), 1_000, NINE + 1_000_000_000L);
      s.quoteResponse(NINE + 2, 2, 61, 71, QuoteStatus.Done, px("4.26"), 1_000, NINE + 2);
      s.quoteResponse(NINE + 3, 2, 61, 71, QuoteStatus.Done, px("4.26"), 1_000, NINE + 2);
      s.quoteRequest(NINE + 4, 2, 62, 301, 2001, Side.Sell, 1_000, "USD");
      s.quote(NINE + 5, 2, 62, 72, 2001, px("4.25"), px("4.26"), 1_000, NINE + 1_000_000_000L);
      s.quoteResponse(NINE + 6, 2, 62, 72, QuoteStatus.Expired, 0, 0, NINE + 6);
    });
    final Trade t = k.trade(new TradeKey(2, TradeSource.Rfq, 71));
    assertEquals(Side.Sell, t.side());
    assertEquals(-1_000, k.position(new PositionKey(2, 20, 2001)).netQuantity());
    assertNull(k.trade(new TradeKey(2, TradeSource.Rfq, 72)));
    assertEquals(1, k.audit(AuditKind.DUPLICATE_IGNORED).size());
    final List<String> events = k.orderRecords().stream().map(r -> r.table() + ":" + r.eventType()).toList();
    assertEquals(List.of("T2:NEWO", "T3:NEWO", "T3:NEWO", "T3:FILL", "T2:NEWO", "T3:NEWO", "T3:NEWO", "T3:EXPI"),
        events);
    assertTrue(k.liveQuotes(NINE).isEmpty());
  }

  @Test
  void quoteResponseWithoutRequestIsAContractBreak() {
    assertThrows(IllegalStateException.class, () -> replay(s ->
        s.quoteResponse(NINE, 2, 61, 71, QuoteStatus.Done, px("4.26"), 1_000, NINE)));
  }

  @Test
  void openOrdersAndLiveQuotesFollowTheJournal() {
    final PositionKeeper k = replay(s -> {
      s.newOrder(NINE, 1, 7, 1001, Side.Buy, px("100"), 300);
      s.executionReport(NINE + 1, 1, 7, 507, 70, 1001, Side.Buy, OrdStatus.New, 0, 0, 300, 0, NINE + 1);
      s.executionReport(NINE + 2, 1, 7, 507, 71, 1001, Side.Buy, OrdStatus.PartiallyFilled, px("100"), 100,
          200, 100, NINE + 2);
      s.newOrder(NINE + 3, 1, 8, 1001, Side.Sell, px("101"), 100);
      s.executionReport(NINE + 4, 1, 8, 508, 80, 1001, Side.Sell, OrdStatus.Cancelled, 0, 0, 0, 0, NINE + 4);
      s.quoteRequest(NINE + 5, 2, 61, 301, 2001, Side.Buy, 1_000, "USD");
      s.quote(NINE + 6, 2, 61, 71, 2001, px("4.25"), px("4.26"), 1_000, NINE + 100);
    });
    final List<OpenOrder> open = k.openOrders();
    assertEquals(1, open.size());
    assertEquals(new OpenOrder(1, 7, 507, 1001, 200, 100), open.get(0));
    assertEquals(1, k.liveQuotes(NINE + 50).size());
    assertTrue(k.liveQuotes(NINE + 100).isEmpty());
    assertEquals(List.of("NEWO", "PARF"),
        k.recordsByVenueOrderId(507).stream().map(OrderRecord::eventType).toList());
    assertEquals(7, k.lastSequence());
    assertEquals("CAME", k.recordsByVenueOrderId(508).get(0).eventType());
  }

  @Test
  void otherTemplatesAreIgnoredAndForeignSchemasRejected() {
    final byte[] heartbeat = new byte[20];
    final UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.wrap(heartbeat));
    new MessageHeaderEncoder().wrap(b, 0).blockLength(12).templateId(1).schemaId(1).version(0);
    final PositionKeeper k = replay(s -> s.raw(NINE, 1, heartbeat));
    assertEquals(1, k.lastSequence());
    assertTrue(k.positions().isEmpty());

    final byte[] foreign = new byte[20];
    new MessageHeaderEncoder().wrap(new UnsafeBuffer(ByteBuffer.wrap(foreign)), 0).blockLength(12)
        .templateId(21).schemaId(9).version(0);
    assertThrows(IllegalStateException.class, () -> replay(s -> s.raw(NINE, 21, foreign)));
  }

  @Test
  void crossingZeroOpensTheRemainderAtTheTradePrice() {
    final PositionKeeper k = replay(s -> {
      fill(s, NINE, 1, Side.Buy, "100", 100, NINE);
      fill(s, NINE + 1, 2, Side.Sell, "102", 300, NINE + 1);
    });
    final Position p = k.position(BOND);
    assertEquals(-200, p.netQuantity());
    assertEquals(0, p.averagePrice().compareTo(java.math.BigDecimal.valueOf(px("102"))));
    assertEquals(2, p.realisedMinor());
    assertEquals(2, p.liveTrades());
    assertEquals(2, k.trades().size());
    assertEquals(1, k.positionKeys().size());
  }

  @Test
  void openingPositionNeedsAKnownInstrument() {
    final PositionKeeper k = new PositionKeeper(Fixtures.books(), Fixtures.catalogue());
    assertThrows(IllegalArgumentException.class,
        () -> k.openPosition(new PositionKey(1, 1, 4242), new OpeningPosition(1, 1)));
    k.openPosition(BOND, new OpeningPosition(-5, px("100")));
    assertEquals(-5, k.position(BOND).netQuantity());
    assertEquals(1, k.audit(AuditKind.OPENING_POSITION).size());
    assertEquals(1, k.audit().size());
  }
}
