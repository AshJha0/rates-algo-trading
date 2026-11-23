package io.ratesalgo.app.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.BookSource;
import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.market.LoopBook;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradePrintDecoder;
import io.ratesalgo.messages.sbe.TradePrintEncoder;
import io.ratesalgo.venues.Venues;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * The simulator v2 universe and the futures overlay: a swap at every risk tenor, each
 * instrument's own tick, the Treasury future with the contract terms of the reference data, and
 * a pricing binding that treats the future as one more instrument.
 */
class V2UniverseTest {

  private static final UsdUniverse V2 = UsdUniverse.simV2();
  private static final long START = 1_793_629_500_000_000_000L;

  @Test
  void thereIsASwapAtEveryRiskTenorWithTheVenuesIds() {
    final List<UsdUniverse.SwapSpec> swaps = V2.swaps();
    assertEquals(KeyRateTenors.COUNT, swaps.size());
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      assertEquals(Math.round(12.0 * KeyRateTenors.years(k)), swaps.get(k).tenorMonths(), "risk tenor " + k);
      assertEquals(KeyRateTenors.label(k), swaps.get(k).tenor());
      assertEquals("USD-SOFR-" + KeyRateTenors.label(k), swaps.get(k).securityId());
    }
    // the reference data's templates keep their ids; the model's other tenors have the session's
    assertEquals(UsdUniverse.SWAP_1Y_ID, swaps.get(0).instrumentId());
    assertEquals(AppIds.SWAP_1Y, swaps.get(0).instrumentId());
    assertEquals(2001, swaps.get(1).instrumentId());
    assertEquals(9105, swaps.get(2).instrumentId());
    assertEquals(2002, swaps.get(3).instrumentId());
    assertEquals(9106, swaps.get(4).instrumentId());
    assertEquals(2003, swaps.get(5).instrumentId());
    assertEquals(9107, swaps.get(6).instrumentId());
    assertEquals(2004, swaps.get(7).instrumentId());
    assertEquals(swaps.get(1).initialMid(), swaps.get(0).initialMid(), "the 1-year opens at the 2-year rate");
    assertNotNull(V2.swap(9106));
    assertNull(V2.swap(2005), "the 12-year template is not in the model's universe");
  }

  @Test
  void treasuriesAndTheFutureCarryTheirOwnTicksAndContractTerms() {
    assertEquals(4, V2.bonds().size());
    // the 2-year trades in 1/256, the others in 1/128
    assertEquals(3_906_250L, V2.bonds().get(0).tick());
    for (int j = 1; j < 4; j++) {
      assertEquals(7_812_500L, V2.bonds().get(j).tick());
    }
    final String[] tenors = {"2Y", "5Y", "10Y", "30Y"};
    final java.util.Set<Integer> onTheRun = new java.util.HashSet<>();
    Venues.treasuries().forEach(i -> onTheRun.add(i.instrumentId()));
    for (int j = 0; j < 4; j++) {
      assertEquals(tenors[j], V2.bonds().get(j).bond().tenor());
      assertTrue(onTheRun.contains(V2.bonds().get(j).instrumentId()), "the on-the-run issue");
    }
    assertEquals(1, V2.futures().size());
    final UsdUniverse.FutureSpec ty = V2.futures().get(0);
    assertEquals("TYZ6", ty.securityId());
    assertEquals(3003, ty.instrumentId());
    assertEquals(100_000L, ty.contract().contractSize());
    assertEquals(15_625_000L, ty.tick(), "1/64 of a point");
    assertEquals(ty.contract().tickSizeMantissa(), ty.tick());
    assertEquals("2026-12", ty.contract().deliveryMonth());
    assertEquals(10_000_000L, ty.contractMinor(), "100,000 of face in cents");
    assertEquals(100L, ty.venueLotMinor(), "one contract on the venue's wire");
    assertEquals(100_000L, ty.deskPerVenue());
    assertEquals(7.0, ty.nodeYears());
    assertEquals(-0.0715, ty.priceChangePerBp());
    assertEquals(110_000_000_000L, ty.initialMid());
    assertEquals(ty, V2.future(3003));
    assertNull(V2.future(2001));

    final int[] ids = V2.instrumentIds();
    assertEquals(13, ids.length);
    assertEquals(3003, ids[12], "swaps, bonds, then futures");
    assertEquals(3003, V2.futureIds()[0]);
    assertEquals("TYZ6", new String(V2.instrumentMap().securityId(3003), java.nio.charset.StandardCharsets.US_ASCII));
    // the standard universe is what it was
    final UsdUniverse v1 = UsdUniverse.standard();
    assertTrue(v1.futures().isEmpty());
    assertEquals(0, v1.futureIds().length);
    assertEquals(v1.swaps().size() + v1.bonds().size(), v1.instrumentIds().length);
  }

  /** A book the test sets by hand. */
  private static final class Book implements BookSource {
    long bid = FairValueSource.NO_VALUE;
    long ask = FairValueSource.NO_VALUE;
    long nanos = FairValueSource.NO_VALUE;

    @Override
    public long bestBid(final int instrumentId) {
      return bid;
    }

    @Override
    public long bestAsk(final int instrumentId) {
      return ask;
    }

    @Override
    public long bidSize(final int instrumentId) {
      return 1;
    }

    @Override
    public long askSize(final int instrumentId) {
      return 1;
    }

    @Override
    public long updateNanos(final int instrumentId) {
      return nanos;
    }
  }

  @Test
  void theOverlayAnswersForTheFutureAndLeavesTheRestToTheBinding() {
    final PricingV2 base = new PricingV2(V2, new ManualClock(START), PricingV2.Options.defaults());
    final FuturesOverlay overlay = new FuturesOverlay(base, 50);
    assertEquals(base, overlay.base());
    assertEquals(V2, overlay.universe());
    assertEquals(base.directory().size() + 1, overlay.directory().size());
    for (int i = 0; i < base.directory().size(); i++) {
      assertEquals(base.directory().byIndex(i), overlay.directory().byIndex(i), "same dense index");
    }
    final Instrument ty = overlay.directory().byId(3003);
    assertEquals(ProductType.FUTURE, ty.type());
    assertEquals(CurrencyCode.USD, ty.currency());
    assertEquals(7.0, ty.tenorYears());
    assertEquals(50 * 10_000_000L, ty.typicalQuantity());

    // no book yet: no opinion
    assertEquals(FairValueSource.NO_VALUE, overlay.fairMid(3003));
    assertEquals(FairValueSource.NO_VALUE, overlay.referenceNanos(3003));
    final Book book = new Book();
    overlay.book(book);
    assertEquals(FairValueSource.NO_VALUE, overlay.fairMid(3003), "an empty book");
    book.bid = 110_000_000_000L;
    assertEquals(FairValueSource.NO_VALUE, overlay.fairMid(3003), "one side only");
    book.ask = 110_015_625_000L;
    book.nanos = START + 5;
    assertEquals(110_007_812_500L, overlay.fairMid(3003), "the mid of the futures book");
    assertEquals(START + 5, overlay.referenceNanos(3003));
    assertEquals(base.fairMid(2003), overlay.fairMid(2003));
    assertEquals(base.referenceNanos(2003), overlay.referenceNanos(2003));

    // ten contracts long: 10 x 100,000 / 100 x -0.0715 = -715 USD a basis point, all at 7 years
    final Dv01Vector ladder = new Dv01Vector(CurrencyCode.USD);
    assertTrue(overlay.pv01(3003, 10 * 10_000_000L, ladder));
    assertEquals(-715.0, ladder.parallel(), 1e-9);
    assertEquals(-715.0, ladder.get(4), 1e-9, "the 7-year bucket");
    final Dv01Vector spread = new Dv01Vector(CurrencyCode.USD);
    assertTrue(overlay.spreadPv01(3003, -10 * 10_000_000L, spread));
    assertEquals(715.0, spread.get(4), 1e-9, "a future carries the Treasury spread as a bond does");

    // swaps and bonds are the binding's own numbers
    final Dv01Vector own = new Dv01Vector(CurrencyCode.USD);
    final Dv01Vector through = new Dv01Vector(CurrencyCode.USD);
    for (final int id : new int[] {2003, V2.bondIds()[2]}) {
      assertEquals(base.pv01(id, 100_000_000L, own), overlay.pv01(id, 100_000_000L, through));
      assertEquals(own.parallel(), through.parallel());
      assertEquals(base.spreadPv01(id, 100_000_000L, own), overlay.spreadPv01(id, 100_000_000L, through));
      assertEquals(own.parallel(), through.parallel());
    }
    assertFalse(overlay.pv01(424_242, 1, through), "an unknown instrument is still unknown");
    assertTrue(overlay.onCurveInput(2003, 3_850_000_000L, START + 10));
  }

  @Test
  void overAVersionOneBindingTheOverlayHasNoSpreadLadderForBonds() {
    final FuturesOverlay overlay = new FuturesOverlay(new AnalyticsPricing(V2, FairValueSource.NO_VALUE), 50);
    final Dv01Vector out = new Dv01Vector(CurrencyCode.USD);
    assertFalse(overlay.spreadPv01(V2.bondIds()[0], 100_000_000L, out));
    assertEquals(0.0, out.gross());
    assertTrue(overlay.spreadPv01(3003, 10_000_000L, out), "the future's is the overlay's own");
  }

  @Test
  void theLoopBookScalesAFuturesVenuesContractsToFace() {
    final FuturesOverlay overlay =
        new FuturesOverlay(new PricingV2(V2, new ManualClock(START), PricingV2.Options.defaults()), 50);
    final LoopBook book = new LoopBook(overlay.directory(), new int[] {AppIds.CLOB_VENUE, AppIds.FUTURES_VENUE});
    assertTrue(book.quantityScale(3003, 100_000L));
    assertFalse(book.quantityScale(424_242, 100_000L), "an unknown instrument");
    assertFalse(book.quantityScale(3003, 0L), "a scale below one");

    final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
    final MessageHeaderEncoder header = new MessageHeaderEncoder();
    final BookUpdateEncoder e = new BookUpdateEncoder();
    e.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.FUTURES_VENUE).instrumentId(3003).venueSeqNo(1).side(Side.Buy)
        .action(BookAction.Add).level((short) 1).quantity(40 * 100L).orderCount(1).timestampNanos(START);
    e.price().mantissa(110_000_000_000L);
    final BookUpdateDecoder d = new BookUpdateDecoder();
    assertTrue(book.apply(d.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder())));
    assertEquals(40 * 10_000_000L, book.bidSize(3003), "forty contracts of face");
    assertEquals(40 * 10_000_000L, book.view(3003, 0.0).depth(AppIds.FUTURES_VENUE, Side.Buy, 0));

    // a bond on the other venue is not scaled
    final int bond = V2.bondIds()[2];
    e.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.CLOB_VENUE).instrumentId(bond).venueSeqNo(1).side(Side.Buy)
        .action(BookAction.Add).level((short) 1).quantity(500_000_000L).orderCount(1).timestampNanos(START);
    e.price().mantissa(99_000_000_000L);
    assertTrue(book.apply(d.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder())));
    assertEquals(500_000_000L, book.bidSize(bond));

    final TradePrintEncoder p = new TradePrintEncoder();
    p.wrapAndApplyHeader(buffer, 0, header).venueId(AppIds.FUTURES_VENUE).instrumentId(3003).venueSeqNo(1)
        .aggressorSide(Side.Sell).quantity(3 * 100L).timestampNanos(START);
    p.price().mantissa(110_000_000_000L);
    final TradePrintDecoder pd = new TradePrintDecoder();
    assertTrue(book.applyTrade(pd.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder())));
    final double ratePerSecond = book.tradeRate(3003, AppIds.FUTURES_VENUE, START + 1_000_000_000L);
    assertTrue(ratePerSecond > 1_000_000.0, "three contracts in a second, counted as face: " + ratePerSecond);
  }
}
