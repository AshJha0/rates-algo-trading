package io.ratesalgo.app.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.BookView;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/** The loop's price ladders, composite top of book and router view. */
class LoopBookTest {

  private static final long PX = 99_000_000_000L;
  private static final long TICK = 7_812_500L;

  private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
  private final BookUpdateEncoder e = new BookUpdateEncoder();
  private final BookUpdateDecoder d = new BookUpdateDecoder();
  private final InMemoryInstrumentDirectory dir = new InMemoryInstrumentDirectory();
  private final LoopBook book;

  LoopBookTest() {
    dir.add(Instrument.bond(1008, "UST10", CurrencyCode.USD, 10.0, 1_000_000_000L));
    book = new LoopBook(dir, new int[] {102, 103});
  }

  private boolean apply(final int venue, final Side side, final BookAction action, final long price, final long qty) {
    e.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder()).venueId(venue).instrumentId(1008).side(side)
        .action(action).quantity(qty).timestampNanos(price);
    e.price().mantissa(price);
    d.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());
    return book.apply(d);
  }

  @Test
  void theCompositeTopTakesTheBestOverVenuesAndSumsSizeAtIt() {
    assertEquals(FairValueSource.NO_VALUE, book.bestBid(1008));
    assertEquals(0L, book.bidSize(1008));
    apply(102, Side.Buy, BookAction.Add, PX, 100);
    apply(102, Side.Buy, BookAction.Add, PX - TICK, 200);
    apply(103, Side.Buy, BookAction.Add, PX, 50);
    apply(102, Side.Sell, BookAction.Add, PX + 2 * TICK, 70);
    apply(103, Side.Sell, BookAction.Add, PX + TICK, 30);
    assertEquals(PX, book.bestBid(1008));
    assertEquals(150, book.bidSize(1008));
    assertEquals(PX + TICK, book.bestAsk(1008));
    assertEquals(30, book.askSize(1008));
    assertEquals(PX + TICK, book.updateNanos(1008));
    apply(102, Side.Buy, BookAction.Change, PX, 400);
    assertEquals(450, book.bidSize(1008));
    apply(103, Side.Buy, BookAction.Delete, PX, 0);
    apply(102, Side.Buy, BookAction.Delete, PX, 0);
    assertEquals(PX - TICK, book.bestBid(1008));
    apply(102, Side.Buy, BookAction.Clear, 0, 0);
    assertEquals(FairValueSource.NO_VALUE, book.bestBid(1008));
    assertEquals(PX + TICK, book.bestAsk(1008), "Clear empties one venue only");
    assertFalse(apply(999, Side.Buy, BookAction.Add, PX, 1), "unknown venue");
    assertEquals(FairValueSource.NO_VALUE, book.bestBid(55));
    assertEquals(FairValueSource.NO_VALUE, book.updateNanos(55));
  }

  @Test
  void aFullSideDropsTheWorstLevelAndTheRouterViewReadsOneVenue() {
    for (int i = 0; i < LoopBook.MAX_LEVELS + 2; i++) {
      apply(102, Side.Sell, BookAction.Add, PX + i * TICK, 10 + i);
    }
    assertEquals(2, book.overflow());
    apply(102, Side.Sell, BookAction.Add, PX - TICK, 5);
    assertEquals(3, book.overflow());
    final BookView view = book.view(1008, 1e9);
    assertEquals(PX - TICK, view.bestAsk(102));
    assertEquals(0L, view.bestBid(102));
    assertEquals(5L, view.depth(102, Side.Sell, 0));
    assertEquals(10L, view.depth(102, Side.Sell, 1));
    assertEquals(0L, view.depth(102, Side.Sell, LoopBook.MAX_LEVELS));
    assertEquals(0L, view.depth(102, Side.Sell, -1));
    assertEquals(0L, view.depth(104, Side.Sell, 0));
    assertEquals(0L, view.bestAsk(104));
    assertEquals(1e9, view.tradeRatePerSecond(102));
    assertEquals(0.0, view.tradeRatePerSecond(104));
    assertThrows(IllegalArgumentException.class, () -> book.view(55, 1.0));
    assertTrue(view.depth(103, Side.Buy, 0) == 0L);
  }
}
