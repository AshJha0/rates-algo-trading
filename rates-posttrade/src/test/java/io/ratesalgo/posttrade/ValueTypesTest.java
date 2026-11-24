package io.ratesalgo.posttrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.clearing.ClearingHouseSimulator;
import io.ratesalgo.posttrade.clearing.ClearingState;
import io.ratesalgo.posttrade.marks.AttributionLine;
import io.ratesalgo.posttrade.marks.FixtureMarkSource;
import io.ratesalgo.posttrade.marks.PnlComponent;
import io.ratesalgo.posttrade.marks.PnlLine;
import io.ratesalgo.posttrade.marks.PnlReconciler;
import io.ratesalgo.posttrade.position.InstrumentCatalogue;
import io.ratesalgo.posttrade.position.InstrumentReference;
import io.ratesalgo.posttrade.recon.ReconItem;
import io.ratesalgo.posttrade.records.OrderRecord;
import io.ratesalgo.posttrade.settlement.BusinessCalendar;
import io.ratesalgo.posttrade.settlement.SettlementEvent;
import io.ratesalgo.posttrade.settlement.SettlementObligation;
import io.ratesalgo.posttrade.trade.BookAssignment;
import io.ratesalgo.posttrade.trade.FixedBookMapper;
import io.ratesalgo.posttrade.trade.PositionKey;
import io.ratesalgo.posttrade.trade.Trade;
import io.ratesalgo.posttrade.trade.TradeKey;
import io.ratesalgo.posttrade.trade.TradeStatus;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Validation, ordering, text forms and builder chaining of the post-trade value types. */
class ValueTypesTest {

  private static final LocalDate D = LocalDate.of(2026, 10, 30);
  private static final BookAssignment BOOK = new BookAssignment(1, 10, "ALG");

  private static Trade trade(final long id, final long qty) {
    return new Trade(new TradeKey(1, TradeSource.Execution, id), 0, id, 1001, Side.Buy, 100, qty, 1, 1, BOOK,
        TradeStatus.LIVE, 1, false);
  }

  @Test
  void keysOrderByEachFieldInTurnAndPrintAsText() {
    final List<TradeKey> keys = new ArrayList<>(List.of(
        new TradeKey(2, TradeSource.Execution, 1), new TradeKey(1, TradeSource.Rfq, 1),
        new TradeKey(1, TradeSource.Execution, 9), new TradeKey(1, TradeSource.Execution, 3)));
    keys.sort(null);
    assertEquals(List.of("1:E:3", "1:E:9", "1:R:1", "2:E:1"), keys.stream().map(TradeKey::text).toList());
    assertEquals(new TradeKey(1, TradeSource.Rfq, 1), TradeKey.parse("1:R:1"));
    final List<PositionKey> pks = new ArrayList<>(List.of(
        new PositionKey(2, 1, 1), new PositionKey(1, 2, 1), new PositionKey(1, 1, 2), new PositionKey(1, 1, 1)));
    pks.sort(null);
    assertEquals(List.of("1/1/1", "1/1/2", "1/2/1", "2/1/1"), pks.stream().map(PositionKey::text).toList());
  }

  @Test
  void valueTypesRefuseImpossibleValues() {
    assertThrows(IllegalArgumentException.class, () -> trade(1, 0));
    assertEquals(1, trade(1, 1).quantity());
    assertThrows(IllegalArgumentException.class,
        () -> new InstrumentReference(1, "X", "USD", "PERC", BigDecimal.ZERO));
    assertThrows(IllegalArgumentException.class,
        () -> new InstrumentReference(1, "X", "USD", "PERC", BigDecimal.valueOf(-1)));
    final OrderRecord r = new OrderRecord(1, "2026-10-30", "T3", "NEWO", "BUYI", "DEAL", false, ",ALG", 7, 0,
        "VENUE-1", "LMT", 1, "USD", "PERC", 1, 0, 0, 0, "", "GDAY", "X", 1001, 0, 1, "");
    assertThrows(IllegalStateException.class, r::toCsv, "a comma first in a field is still a comma");
  }

  @Test
  void buildersReturnThemselvesSoCallsChain() {
    final InstrumentCatalogue c = new InstrumentCatalogue()
        .add(new InstrumentReference(1, "A", "USD", "PERC", BigDecimal.ONE))
        .add(new InstrumentReference(2, "B", "EUR", "PERC", BigDecimal.TEN));
    assertEquals("EUR", c.get(2).currency());
    final FixedBookMapper m = new FixedBookMapper(BOOK).with(1, new BookAssignment(2, 20, "X")).with(2, BOOK);
    assertEquals(20, m.assign(1, 1, 0).account());
    final FixtureMarkSource marks = new FixtureMarkSource().put(1, D, 5).put(2, D, 6);
    assertEquals(6, marks.closingMark(2, D).getAsLong());
  }

  @Test
  void csvErrorsNameTheLineCountingCommentsAndBlanks() {
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AttributionLine.read(
        new StringReader("# a comment\n\n" + AttributionLine.HEADER + "\n2026-10-30,1\n"), "attr.csv"));
    assertTrue(e.getMessage().startsWith("attr.csv line 4: 2 fields, expected 6"), e.getMessage());
  }

  @Test
  void aSettlementDateIsNeverMovedByAZeroLag() {
    assertEquals(D, BusinessCalendar.WEEKENDS_ONLY.addBusinessDays(D, 0));
    assertEquals(D.plusDays(3), BusinessCalendar.WEEKENDS_ONLY.addBusinessDays(D, 1), "Friday to Monday");
  }

  @Test
  void anObligationThatOnlySawRefusedEventsHasNeverFailed() {
    final SettlementObligation o =
        new SettlementObligation(new TradeKey(1, TradeSource.Execution, 9), Side.Sell, D.plusDays(3), 100);
    o.apply(SettlementEvent.SETTLE, D); // before the settlement date: refused, but in the history
    assertEquals(1, o.history().size());
    assertEquals(0, o.daysFailed(D.plusDays(10)));
  }

  @Test
  void theClearingLimitIsInclusive() {
    final List<String> rejected = new ArrayList<>();
    final ClearingHouseSimulator ccp = new ClearingHouseSimulator((k, why) -> rejected.add(why)).limit(10, 300);
    assertEquals(ClearingState.ACCEPTED, ccp.submit(trade(1, 200), 1));
    assertEquals(ClearingState.ACCEPTED, ccp.submit(trade(2, 100), 2), "exactly at the limit");
    assertEquals(ClearingState.REJECTED, ccp.submit(trade(3, 1), 3));
    assertEquals(List.of("account 10 limit 300 used 300 trade 1"), rejected);
  }

  @Test
  void attributionWithNoOfficialLineIsADifference() {
    final PositionKey k = new PositionKey(1, 10, 1001);
    final PnlReconciler.Result r = PnlReconciler.reconcile(
        List.of(new PnlLine(D, k, 100, 5, 7, 0, 0)),
        List.of(new AttributionLine(D, k, PnlComponent.REALISED, 7),
            new AttributionLine(D, new PositionKey(2, 20, 2001), PnlComponent.CARRY, 3)));
    assertEquals(1, r.differences().size());
    assertEquals(3, -r.differences().get(0).deltaMinor());
  }

  @Test
  void recordsWithoutATradeKeyRebuildNoTradeAndItemsDescribeThemselves() {
    final OrderRecord newo = new OrderRecord(1, "2026-10-30", "T3", "FILL", "BUYI", "DEAL", false, "ALG", 7, 0,
        "VENUE-1", "LMT", 1, "USD", "PERC", 0, 5, 0, 0, "", "GDAY", "X", 1001, 0, 1, "");
    assertTrue(ReconItem.fromRecords(List.of(newo)).isEmpty());
    assertEquals("Buy 5@7 t=9", new ReconItem("1:E:1", 1001, Side.Buy, 5, 7, 9).describe());
  }

  @Test
  void anAmendmentRebuildsOnlyATradeThatIsThere() {
    final OrderRecord fill = new OrderRecord(1, "2026-10-30", "T3", "FILL", "BUYI", "DEAL", false, "ALG", 7, 0,
        "VENUE-1", "LMT", 100, "USD", "PERC", 0, 5, 0, 9, "1", "GDAY", "X", 1001, 0, 1, "1:E:1");
    final OrderRecord amend = new OrderRecord(2, "2026-10-30", "T3", "AMND", "BUYI", "DEAL", false, "ALG", 7, 0,
        "VENUE-1", "LMT", 101, "USD", "PERC", 0, 4, 0, 10, "1", "GDAY", "X", 1001, 0, 2, "1:E:1");
    final OrderRecord stray = new OrderRecord(3, "2026-10-30", "T3", "AMND", "BUYI", "DEAL", false, "ALG", 8, 0,
        "VENUE-1", "LMT", 101, "USD", "PERC", 0, 4, 0, 10, "2", "GDAY", "X", 1001, 0, 3, "1:E:2");
    assertEquals(List.of(new ReconItem("1:E:1", 1001, Side.Buy, 4, 101, 9)),
        ReconItem.fromRecords(List.of(fill, amend, stray)));
  }
}
