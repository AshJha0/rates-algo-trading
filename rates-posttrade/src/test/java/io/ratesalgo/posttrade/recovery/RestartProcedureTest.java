package io.ratesalgo.posttrade.recovery;

import static io.ratesalgo.posttrade.Fixtures.NINE;
import static io.ratesalgo.posttrade.Fixtures.px;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.posttrade.Fixtures;
import io.ratesalgo.posttrade.chaos.JournalScript;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import io.ratesalgo.posttrade.position.PositionKeeper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The restart procedure's order of steps and its decision to re-enable quoting. */
class RestartProcedureTest {

  @TempDir Path dir;

  private final List<String> log = new ArrayList<>();

  private final class FakeQuoting implements QuotingControl {
    private boolean on = true;

    @Override
    public void disable(final String reason) {
      log.add("disable");
      on = false;
    }

    @Override
    public void enable(final String reason) {
      log.add("enable");
      on = true;
    }

    @Override
    public boolean enabled() {
      return on;
    }
  }

  private final class FakeVenue implements VenueStatusPort {
    private final List<OrderStatus> orders;
    private final List<QuoteStatus> quotes;

    FakeVenue(final List<OrderStatus> orders, final List<QuoteStatus> quotes) {
      this.orders = orders;
      this.quotes = quotes;
    }

    @Override
    public List<OrderStatus> orderMassStatus(final int venueId) {
      log.add("order-status " + venueId);
      return venueId == 1 ? orders : List.of();
    }

    @Override
    public List<QuoteStatus> quoteStatus(final int venueId) {
      log.add("quote-status " + venueId);
      return venueId == 2 ? quotes : List.of();
    }

    @Override
    public void cancelOrder(final int venueId, final long clOrdId, final long venueOrderId) {
      log.add("cancel-order " + clOrdId);
    }

    @Override
    public void cancelQuote(final int venueId, final long quoteId) {
      log.add("cancel-quote " + quoteId);
    }
  }

  private Path journal() {
    final Path j = dir.resolve("trading.raj");
    try (JournalScript s = new JournalScript(j, 1 << 16, SyntheticDay.MIDNIGHT)) {
      s.newOrder(NINE, 1, 7, 1001, Side.Buy, px("100"), 300);
      s.executionReport(NINE + 1, 1, 7, 507, 70, 1001, Side.Buy, OrdStatus.New, 0, 0, 300, 0, NINE + 1);
      s.newOrder(NINE + 2, 1, 8, 1001, Side.Buy, px("100"), 300);
      s.executionReport(NINE + 3, 1, 8, 508, 80, 1001, Side.Buy, OrdStatus.New, 0, 0, 300, 0, NINE + 3);
      s.quoteRequest(NINE + 4, 2, 61, 301, 2001, Side.Buy, 1_000, "USD");
      s.quote(NINE + 5, 2, 61, 71, 2001, px("4.25"), px("4.26"), 1_000, NINE + 1_000_000_000_000L);
    }
    return j;
  }

  @Test
  void reconciledVenuesReEnableQuotingOnlyAtTheEnd() {
    final List<String> seen = new ArrayList<>();
    final PositionKeeper keeper = new PositionKeeper(Fixtures.books(), Fixtures.catalogue());
    final FakeVenue venue = new FakeVenue(
        List.of(new VenueStatusPort.OrderStatus(7, 507, 1001, 300, 0),
            new VenueStatusPort.OrderStatus(99, 599, 1001, 100, 0)),
        List.of(new VenueStatusPort.QuoteStatus(71, 2001, NINE + 1_000_000_000_000L)));
    final RestartProcedure.Report r = new RestartProcedure(keeper, venue, new FakeQuoting())
        .run(journal(), new int[] {1, 2}, NINE + 10, (s, t, id, v, b, o, l) -> seen.add("frame " + s));
    assertTrue(r.quotingEnabled());
    assertEquals(6, r.replayedFrames());
    assertEquals(6, r.lastSequence());
    assertEquals(6, seen.size());
    assertEquals(List.of("disable", "order-status 1", "cancel-order 99", "quote-status 1", "order-status 2",
        "quote-status 2", "cancel-quote 71", "enable"), log);
    assertTrue(r.actions().contains("venue 1: order 8 finished while down, await drop copy"), r.actions()::toString);
    assertTrue(r.actions().contains("venue 2: journal quote 71 treated as withdrawn"), r.actions()::toString);
    assertTrue(r.blockingDiscrepancies().isEmpty());
  }

  @Test
  void missingFillsKeepQuotingDisabled() {
    final PositionKeeper keeper = new PositionKeeper(Fixtures.books(), Fixtures.catalogue());
    final FakeVenue venue = new FakeVenue(
        List.of(new VenueStatusPort.OrderStatus(7, 507, 1001, 100, 200)), List.of());
    final RestartProcedure.Report r =
        new RestartProcedure(keeper, venue, new FakeQuoting()).run(journal(), new int[] {1}, NINE);
    assertFalse(r.quotingEnabled());
    assertEquals(1, r.blockingDiscrepancies().size());
    assertFalse(log.contains("enable"));
    assertEquals("disable", log.get(0));
  }
}
