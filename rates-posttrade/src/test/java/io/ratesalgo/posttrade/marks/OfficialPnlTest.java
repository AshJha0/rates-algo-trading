package io.ratesalgo.posttrade.marks;

import static io.ratesalgo.posttrade.Fixtures.NINE;
import static io.ratesalgo.posttrade.Fixtures.px;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.journal.Replayer;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.AmendReason;
import io.ratesalgo.messages.sbe.BustReason;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.Fixtures;
import io.ratesalgo.posttrade.chaos.JournalScript;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import io.ratesalgo.posttrade.position.InstrumentCatalogue;
import io.ratesalgo.posttrade.position.OpeningPosition;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.trade.PositionKey;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance: official end-of-day P&amp;L reconciles to the fixture attribution to the cent.
 * The fixture day carries an opening position, an amended trade, a duplicate fill, a busted
 * trade and two RFQ trades; the expected figures are worked by hand in API_SPEC.md 17.5.
 */
class OfficialPnlTest {

  static final LocalDate DATE = SyntheticDay.DATE;
  static final long MIN = 60_000_000_000L;

  /** Writes the fixture day's journal. */
  static Path fixtureJournal(final Path dir) {
    final Path j = dir.resolve("fixture.raj");
    try (JournalScript s = new JournalScript(j, 1 << 16, SyntheticDay.MIDNIGHT)) {
      long t = NINE;
      // trade 1: buy 5,000,000.00 face of 1001, booked at 99.61 then corrected to 99.60
      s.newOrder(t, 1, 1, 1001, Side.Buy, px("99.61"), 500_000_000L);
      s.executionReport(t + 1, 1, 1, 501, 9001, 1001, Side.Buy, OrdStatus.Filled, px("99.61"),
          500_000_000L, 0, 500_000_000L, t + 1);
      s.amend(t + MIN, 1, TradeSource.Execution, 9001, px("99.60"), 500_000_000L, 77, AmendReason.PriceCorrection);
      // trade 2: sell 6,000,000.00 at 99.70, delivered twice
      t += 10 * MIN;
      s.newOrder(t, 1, 2, 1001, Side.Sell, px("99.70"), 600_000_000L);
      s.executionReport(t + 1, 1, 2, 502, 9002, 1001, Side.Sell, OrdStatus.Filled, px("99.70"),
          600_000_000L, 0, 600_000_000L, t + 1);
      s.executionReport(t + 2, 1, 2, 502, 9002, 1001, Side.Sell, OrdStatus.Filled, px("99.70"),
          600_000_000L, 0, 600_000_000L, t + 1);
      // trade 3: busted by the venue
      t += 10 * MIN;
      s.newOrder(t, 1, 3, 1001, Side.Buy, px("98.00"), 200_000_000L);
      s.executionReport(t + 1, 1, 3, 503, 9003, 1001, Side.Buy, OrdStatus.Filled, px("98.00"),
          200_000_000L, 0, 200_000_000L, t + 1);
      s.bust(t + MIN, 1, TradeSource.Execution, 9003, 503, 1001, BustReason.ErroneousTrade);
      // RFQ 1: client sells 100mm of the swap at our bid 4.25, so we buy (pay fixed)
      t += 10 * MIN;
      s.quoteRequest(t, 2, 61, 301, 2001, Side.Sell, 10_000_000_000L, "USD");
      s.quote(t + 1, 2, 61, 71, 2001, px("4.25"), px("4.26"), 10_000_000_000L, t + 10 * MIN);
      s.quoteResponse(t + 2, 2, 61, 71, QuoteStatus.Done, px("4.25"), 10_000_000_000L, t + 2);
      // RFQ 2: client buys 40mm at our ask 4.26, so we sell
      t += 10 * MIN;
      s.quoteRequest(t, 2, 62, 302, 2001, Side.Buy, 4_000_000_000L, "USD");
      s.quote(t + 1, 2, 62, 72, 2001, px("4.25"), px("4.26"), 4_000_000_000L, t + 10 * MIN);
      s.quoteResponse(t + 2, 2, 62, 72, QuoteStatus.Done, px("4.26"), 4_000_000_000L, t + 2);
    }
    return j;
  }

  static PositionKeeper bookFixture(final Path journal, final InstrumentCatalogue catalogue) {
    final PositionKeeper keeper = new PositionKeeper(Fixtures.books(), catalogue);
    keeper.openPosition(new PositionKey(1, 10, 1001), new OpeningPosition(1_000_000_000L, px("99.50")));
    new Replayer(new ManualClock(0)).replay(journal, keeper);
    return keeper;
  }

  @Test
  void officialPnlReconcilesToAttributionToTheCent(@TempDir final Path dir) {
    final InstrumentCatalogue catalogue = Fixtures.catalogue();
    final PositionKeeper keeper = bookFixture(fixtureJournal(dir), catalogue);
    final List<PnlLine> official =
        new EndOfDayPnl(
                FixtureMarkSource.read(Fixtures.reader("marks.csv"), "marks.csv"),
                FixtureCarrySource.read(Fixtures.reader("carry.csv"), "carry.csv"),
                catalogue)
            .compute(DATE, keeper);
    final List<AttributionLine> attribution =
        AttributionLine.read(Fixtures.reader("attribution.csv"), "attribution.csv");

    final PnlReconciler.Result result = PnlReconciler.reconcile(official, attribution);
    assertTrue(result.reconciled(), () -> "differences: " + result.differences());
    assertEquals(6, result.componentsCompared());

    final PnlLine bond = official.get(0);
    assertEquals(900_000_000L, bond.netQuantity());
    assertEquals(2_081_500L, bond.totalMinor());
    assertEquals(px("99.65"), bond.mark());
    final PnlLine swap = official.get(1);
    assertEquals(6_000_000_000L, swap.netQuantity());
    assertEquals(13_608_400L, swap.totalMinor());
  }

  @Test
  void aOneCentDifferenceIsReported(@TempDir final Path dir) {
    final InstrumentCatalogue catalogue = Fixtures.catalogue();
    final PositionKeeper keeper = bookFixture(fixtureJournal(dir), catalogue);
    final List<PnlLine> official =
        new EndOfDayPnl(
                FixtureMarkSource.read(Fixtures.reader("marks.csv"), "marks.csv"),
                FixtureCarrySource.read(Fixtures.reader("carry.csv"), "carry.csv"),
                catalogue)
            .compute(DATE, keeper);
    final List<AttributionLine> off = new java.util.ArrayList<>(AttributionLine.fromPnl(official));
    final AttributionLine first = off.get(0);
    off.set(0, new AttributionLine(first.date(), first.key(), first.component(), first.amountMinor() + 1));
    final PnlReconciler.Result result = PnlReconciler.reconcile(official, off);
    assertFalse(result.reconciled());
    assertEquals(1, result.differences().size());
    assertEquals(-1, result.differences().get(0).deltaMinor());
  }

  @Test
  void aMissingMarkStopsOfficialPnl(@TempDir final Path dir) {
    final InstrumentCatalogue catalogue = Fixtures.catalogue();
    final PositionKeeper keeper = bookFixture(fixtureJournal(dir), catalogue);
    final EndOfDayPnl eod = new EndOfDayPnl(new FixtureMarkSource(), (i, d) -> java.math.BigDecimal.ZERO, catalogue);
    final MissingMarkException e = assertThrows(MissingMarkException.class, () -> eod.compute(DATE, keeper));
    assertEquals(1001, e.instrumentId());
  }

  @Test
  void flatPositionsNeedNoMark() {
    final InstrumentCatalogue catalogue = Fixtures.catalogue();
    final PositionKeeper keeper = new PositionKeeper(Fixtures.books(), catalogue);
    keeper.openPosition(new PositionKey(1, 10, 1002), new OpeningPosition(0, px("99")));
    final List<PnlLine> lines = new EndOfDayPnl(new FixtureMarkSource(), (i, d) -> java.math.BigDecimal.ONE, catalogue)
        .compute(DATE, keeper);
    assertEquals(0, lines.get(0).totalMinor());
  }

  @Test
  void attributionFilesAreChecked() {
    assertThrows(IllegalArgumentException.class,
        () -> AttributionLine.read(new java.io.StringReader("date,book\n"), "bad"));
    assertThrows(IllegalArgumentException.class,
        () -> AttributionLine.read(new java.io.StringReader(AttributionLine.HEADER + "\n1,2\n"), "bad"));
    assertThrows(IllegalArgumentException.class,
        () -> AttributionLine.read(new java.io.StringReader("# only a comment\n"), "bad"));
    assertEquals(PnlComponent.CARRY, PnlComponent.parse("carry"));
    assertEquals("realised", PnlComponent.REALISED.fileName());
  }
}
