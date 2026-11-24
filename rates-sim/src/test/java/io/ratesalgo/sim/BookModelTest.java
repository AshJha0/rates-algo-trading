package io.ratesalgo.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.sim.SimParams.Scalar;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests A9 (impact) and A10 (book resilience and taker volume) of the design, the
 * book's part of A2 (polling invariance), and the queue rules of section 8. Seeds: base
 * 28,000,000. Expected values are computed from the parameter file.
 */
class BookModelTest {

  private static final long SECOND = MarketSpec.STEP_NANOS;
  private static final long TEN_AM = 3L * 3600L * SECOND;

  private static SimParams p;
  private static MarketSpec spec;
  private static int note;
  private static int future;
  private static double tick;
  private static double noteImpact50;

  @BeforeAll
  static void load() {
    p = SimParams.loadDefault();
    spec = new MarketSpec(p);
    note = spec.indexOf("UST-10Y");
    future = spec.indexOf("TY");
    tick = spec.instrument(note).tick();
    noteImpact50 = impact(note, 50e6);
  }

  /** I = Y x sigma x sqrt(Q / V) from the parameter file: sigma = |pcpb| x daily yield sd at the node. */
  private static double impact(final int instrument, final double units) {
    final SimInstrument inst = spec.instrument(instrument);
    double variance = 0.0;
    for (int j = 0; j < SimParams.FACTORS; j++) {
      variance += Math.pow(spec.loading(instrument, j) * p.factorDailySdBp(j), 2);
    }
    final double volume =
        inst.kind() == SimInstrument.Kind.FUTURE
            ? p.get(Scalar.FUTURES_DAILY_VOLUME_CONTRACTS)
            : p.get(Scalar.TREASURY_DAILY_VOLUME_USD_BN) * 1e9;
    return p.get(Scalar.IMPACT_Y) * Math.abs(inst.priceChangePerBp()) * Math.sqrt(variance) * Math.sqrt(units / volume);
  }

  private static MarketModel session(final int n) {
    return new MarketModel(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 40_000 + n), DayType.NONE);
  }

  // ------------------------------------------------------------------ A9

  /**
   * A single 50 mm market order in the 10-year note at 10:00, against the same session without
   * it. All four statements are exact in the model, so the tolerances are rounding only (the
   * design allows 1 percent on the first).
   */
  @Test
  void a9_singleOrderMovesTheBookByTheDeclaredLaw() {
    // the design's scale check: 0.7 x 0.43 x sqrt(0.00125) = 0.0107 points, about 0.13 bp
    assertEquals(0.0107, noteImpact50, 0.0001);
    final double permanent = p.get(Scalar.IMPACT_PERMANENT_SHARE);
    final MarketModel with = session(0);
    final MarketModel without = session(0);
    final BookModel book = with.book(note);
    final BookModel twin = without.book(note);
    book.advanceTo(TEN_AM);
    twin.advanceTo(TEN_AM);
    final double before = book.displacedMid();
    final double askBefore = book.bestAsk();
    assertEquals(twin.displacedMid(), before, 0.0);

    final BookModel.Execution e = book.submit(TEN_AM, true, 50e6, Double.NaN, true);
    assertEquals(50e6, e.filledUnits(), 0.0);
    assertEquals(0, e.restingId());
    // matched at the displayed prices before the book moved: from the touch, at most two levels up
    assertTrue(e.averagePrice() >= askBefore && e.averagePrice() <= askBefore + 2 * tick, "average " + e.averagePrice());
    // the displaced mid moves by I
    assertEquals(noteImpact50, book.displacedMid() - before, 1e-12);
    assertEquals(50e6, book.ownDayVolume(), 0.0);
    assertEquals(50e6, book.ownRecentVolume(), 0.0);

    // after one half-life the transient part is half
    final long halfLife = (long) (p.get(Scalar.IMPACT_HALF_LIFE_S) * SECOND);
    book.advanceTo(TEN_AM + halfLife);
    twin.advanceTo(TEN_AM + halfLife);
    assertEquals(
        0.5 * (1.0 - permanent) * noteImpact50, book.transientDisplacement() - twin.transientDisplacement(), 1e-12);

    // after ten minutes every instrument's latent mid differs by exactly the permanent part through L
    final long later = TEN_AM + 600 * SECOND;
    book.advanceTo(later);
    final SimInstrument traded = spec.instrument(note);
    final double offset = permanent * noteImpact50 / (traded.priceChangePerBp() * spec.loading(note, 0));
    assertTrue(offset < 0.0, "buying a bond lowers yields");
    assertEquals(offset, with.levelOffset(later), 1e-15);
    assertEquals(0.0, with.levelOffset(TEN_AM - 1), 0.0);
    assertEquals(offset, with.levelOffset(TEN_AM), 1e-15);
    for (int i = 0; i < spec.instruments(); i++) {
      final SimInstrument inst = spec.instrument(i);
      assertEquals(
          inst.priceChangePerBp() * spec.loading(i, 0) * offset,
          with.latentMid(i, later) - without.latentMid(i, later),
          1e-12,
          inst.key());
      // before the order the two sessions are the same session
      assertEquals(without.latentMid(i, TEN_AM - 1), with.latentMid(i, TEN_AM - 1), 0.0);
    }
    assertEquals(permanent * noteImpact50, with.latentMid(note, later) - without.latentMid(note, later), 1e-12);
    // the exogenous path is untouched
    assertEquals(without.replayHash(), with.replayHash());
  }

  /**
   * Twenty 2.5 mm orders one second apart against the single 50 mm order: sub-additive. The slices
   * leave the same permanent impact as the block (the law is of the total, not of each slice), and
   * the book is never displaced by more than the block displaced it, because the transient part of
   * the early slices has started to decay. Under a law applied to each slice on its own the slices
   * would leave sqrt(20) = 4.5 times the block's permanent impact.
   *
   * <p>"Cost" here is impact, which is what the square-root law is about. In execution price the
   * comparison in this book goes the other way: the block is matched against five displayed
   * levels that have not yet reacted, the slices each pay the touch of a book their predecessors
   * moved (design document, "Deviations and clarifications").
   */
  @Test
  void a9_slicedOrderHasTheImpactOfItsTotalAndNoMore() {
    final double permanent = p.get(Scalar.IMPACT_PERMANENT_SHARE);
    final MarketModel block = session(0);
    final MarketModel sliced = session(0);
    final MarketModel none = session(0);
    block.book(note).submit(TEN_AM, true, 50e6, Double.NaN, true);
    final BookModel book = sliced.book(note);
    final BookModel twin = none.book(note);
    double worst = 0.0;
    for (int n = 0; n < 20; n++) {
      final long t = TEN_AM + n * SECOND;
      assertEquals(2.5e6, book.submit(t, true, 2.5e6, Double.NaN, true).filledUnits(), 0.0);
      twin.advanceTo(t);
      final double own = book.displacedMid() - twin.displacedMid();
      assertTrue(own > 0.0);
      worst = Math.max(worst, own);
    }
    final long end = TEN_AM + 19 * SECOND;
    assertEquals(block.levelOffset(end), sliced.levelOffset(end), 1e-15);
    assertTrue(worst < noteImpact50, "largest displacement " + worst + " against the block's " + noteImpact50);
    assertTrue(worst > permanent * noteImpact50);
    assertEquals(50e6, book.ownDayVolume(), 1e-3);
    assertTrue(book.ownRecentVolume() < 50e6 && book.ownRecentVolume() > 25e6);
    // a per-slice law would have moved the level factor sqrt(20) times as far
    assertEquals(
        permanent * noteImpact50 / (spec.instrument(note).priceChangePerBp() * spec.loading(note, 0)),
        sliced.levelOffset(end),
        1e-15);

    // selling the 50 mm back undoes the permanent part: no net trade, no permanent move
    book.submit(end + 3600 * SECOND, false, 50e6, Double.NaN, true);
    assertEquals(0.0, sliced.levelOffset(end + 3600 * SECOND), 1e-15);
    assertEquals(0.0, book.ownDayVolume(), 1e-3);
    assertTrue(book.ownRecentVolume() < 0.0);
  }

  @Test
  void a9_futureOrderMovesEveryInstrumentThroughTheLevelLoading() {
    final MarketModel m = session(1);
    final BookModel ty = m.book(future);
    assertEquals(future, ty.instrument());
    final double impact = impact(future, 2000.0);
    ty.advanceTo(TEN_AM);
    final double before = ty.displacedMid();
    final double noteBefore = m.latentMid(note, TEN_AM);
    final BookModel.Execution e = ty.submit(TEN_AM, false, 2000.0, Double.NaN, true);
    assertEquals(2000.0, e.filledUnits(), 0.0);
    assertEquals(-impact, ty.displacedMid() - before, 1e-12);
    // selling futures raises yields: X1 positive, the note's latent mid falls
    final double offset = m.levelOffset(TEN_AM);
    assertTrue(offset > 0.0);
    assertEquals(
        spec.instrument(note).priceChangePerBp() * spec.loading(note, 0) * offset,
        m.latentMid(note, TEN_AM) - noteBefore,
        1e-12);
    // permanent impact is applied in time order across the session's books
    final BookModel other = m.book(note);
    assertThrows(IllegalArgumentException.class, () -> other.submit(TEN_AM - SECOND, true, 1e6, Double.NaN, true));
  }

  // ------------------------------------------------------------------ A10

  /**
   * Depth consumed at the touch is half restored after one half-life with no further trades. The
   * test looks for a 30-second stretch of the session with no other taker and an unmoved best ask,
   * consumes 20 mm at the ask and reads what is left after the half-life.
   */
  @Test
  void a10_consumedDepthComesBackWithTheHalfLife() {
    final int halfLife = (int) p.get(Scalar.IMPACT_HALF_LIFE_S);
    final BookModel probe = session(2).book(note);
    int start = -1;
    for (int k = 3600; k < spec.steps() - halfLife && start < 0; k++) {
      if (probe.takerStart(k + halfLife + 1) != probe.takerStart(k)) {
        continue;
      }
      // quiet in the flow; the best ask must also stay where it is
      final BookModel check = session(2).book(note);
      check.advanceTo(k * SECOND);
      final double ask = check.bestAsk();
      boolean still = true;
      for (int s = 1; s <= halfLife && still; s++) {
        check.advanceTo((k + s) * SECOND);
        still = check.bestAsk() == ask;
      }
      if (still) {
        start = k;
      }
    }
    assertTrue(start > 0, "no quiet half-life in the session");

    final BookModel book = session(2).book(note);
    book.advanceTo(start * SECOND);
    final double earlier = book.consumedUnits(false, 0);
    final double target = book.targetUnits(0);
    assertEquals(30e6, target, 0.0);
    book.consume(false, book.bestAsk(), 20e6);
    assertEquals(earlier + 20e6, book.consumedUnits(false, 0), 1e-3);
    assertEquals(Math.max(0.0, target - earlier - 20e6), book.displayedUnits(false, 0), 1e-3);
    book.advanceTo((start + halfLife) * SECOND);
    assertEquals(0.5 * (earlier + 20e6), book.consumedUnits(false, 0), 1e-3);
    assertEquals(target - 0.5 * (earlier + 20e6), book.displayedUnits(false, 0), 1e-3);
    // a price outside the five levels has no provider size to consume
    book.consume(false, book.bestAsk() + 20 * tick, 5e6);
    book.consume(true, book.bestBid() + 20 * tick, 5e6);
    for (int l = 0; l < p.bookLevels(); l++) {
      assertTrue(book.consumedUnits(false, l) <= 0.5 * (earlier + 20e6) + 1e-3);
      assertEquals(book.bestAsk() + l * tick, book.levelPrice(false, l), 0.0);
      assertEquals(book.bestBid() - l * tick, book.levelPrice(true, l), 0.0);
      assertEquals(target * p.depthMultiple(l), book.targetUnits(l), 0.0);
    }
  }

  /**
   * Other takers over 100 sessions, for the 10-year note and for TY.
   *
   * <p>Volume within 5 percent of V_i. A session has about 4,600 note trades of lognormal size
   * (log sd 1, coefficient of variation 1.3), so one session's volume has relative sd 2.4 percent
   * and the mean of 100 has 0.24 percent; for TY (18,000 trades) less.
   *
   * <p>Fraction of trades in the direction of the latent mid's move over the next 5 s: 0.5 + theta
   * x (share of trades whose step has a non-zero move) within 0.01. With 460,000 note trades the
   * standard error is 0.0007.
   */
  @Test
  void a10_otherTakersTradeTheDeclaredVolumeWithTheDeclaredSkill() {
    final int look = (int) p.get(Scalar.TAKER_LOOK_AHEAD_S);
    for (final int instrument : new int[] {note, future}) {
      final int b = spec.bookOf(instrument);
      double volume = 0.0;
      long trades = 0;
      long withMove = 0;
      long nonZero = 0;
      for (int s = 0; s < 100; s++) {
        final MarketModel m = session(100 + s);
        final BookModel book = m.book(instrument);
        for (int k = 0; k < spec.steps(); k++) {
          final double move =
              m.exogenousMid(instrument, Math.min(spec.steps(), k + look)) - m.exogenousMid(instrument, k);
          for (int n = book.takerStart(k); n < book.takerStart(k + 1); n++) {
            volume += book.takerUnits(n);
            trades++;
            nonZero += move != 0.0 ? 1 : 0;
            withMove += move != 0.0 && book.takerBuys(n) == move > 0.0 ? 1 : 0;
            assertEquals(0.0, book.takerUnits(n) % spec.instrument(instrument).minQuantity(), 0.0);
          }
        }
      }
      assertEquals(1.0, volume / 100.0 / spec.dailyVolumeUnits(b), 0.05, spec.instrument(instrument).key());
      final double expected = 0.5 + p.get(Scalar.TAKER_SIGN_SKILL) * nonZero / trades;
      // trades in a step with no move are half buys; they are counted as not with the move
      assertEquals(expected - 0.5 * (trades - nonZero) / trades, (double) withMove / trades, 0.01);
      assertTrue((double) nonZero / trades > 0.99);
      assertEquals(spec.dailyVolumeUnits(b) / spec.takerMeanUnits(b), trades / 100.0, 0.02 * trades / 100.0);
    }
    assertEquals(40e9, spec.dailyVolumeUnits(spec.bookOf(note)), 0.0);
    assertEquals(1.5e6, spec.dailyVolumeUnits(spec.bookOf(future)), 0.0);
    // TY: 150 bn of face a day against the note's 40 bn, so 3.75 times the note's 30 mm at the touch
    assertEquals(1125.0, spec.touchTargetUnits(spec.bookOf(future)), 0.0);
    assertEquals(50.0, spec.takerMedianUnits(spec.bookOf(future)), 0.0);
    // mean of a lognormal with median 5 mm and log sd 1 is 8.24 mm; rounding up to 1 mm adds half a million
    assertEquals(8.74e6, spec.takerMeanUnits(spec.bookOf(note)), 0.01e6);
    // with a lot far below the median the rounding adds half a lot to the lognormal mean
    assertEquals(50.0 * Math.exp(0.5) + 0.005, MarketSpec.meanRoundedUp(50.0, 1.0, 0.01), 1e-3);
  }

  // ------------------------------------------------------------------ A2, the book's part

  /** One session's book with the desk's orders at fixed times, advanced at the given interval. */
  private static List<Double> run(final long pollNanos) {
    final MarketModel m = session(3);
    final BookModel book = m.book(note);
    final long buyAt = TEN_AM + 250_000_000L;
    final long restAt = TEN_AM + 61 * SECOND + 10_000_000L;
    final long end = TEN_AM + 1200 * SECOND;
    final List<Double> out = new ArrayList<>();
    boolean bought = false;
    boolean rested = false;
    for (long t = TEN_AM - 5 * SECOND; ; t = Math.min(end, t + pollNanos)) {
      if (!bought && t >= buyAt) {
        final BookModel.Execution e = book.submit(buyAt, true, 40e6, Double.NaN, true);
        out.add(e.notional());
        bought = true;
      }
      if (!rested && t >= restAt) {
        book.advanceTo(restAt);
        out.add((double) book.submit(restAt, false, 10e6, book.bestAsk(), false).restingId());
        rested = true;
      }
      book.advanceTo(Math.max(t, rested ? restAt : bought ? buyAt : t));
      if (t == end) {
        break;
      }
    }
    out.add(book.displacedMid());
    out.add(book.transientDisplacement());
    out.add(book.takerFilledUnits());
    out.add(book.bestBid());
    out.add(book.bestAsk());
    out.add(m.levelOffset(end));
    for (int l = 0; l < p.bookLevels(); l++) {
      out.add(book.displayedUnits(true, l));
      out.add(book.displayedUnits(false, l));
    }
    for (final BookModel.PassiveFill f : book.drainPassiveFills()) {
      out.add((double) f.nanos());
      out.add(f.quantityUnits());
      out.add(f.price());
    }
    return out;
  }

  /** The book's state does not depend on how often it is advanced: 10 ms, 100 ms, 1 s, 7 s or one jump. */
  @Test
  void a2_bookStateDoesNotDependOnPolling() {
    final List<Double> perStep = run(SECOND);
    assertEquals(perStep, run(10_000_000L));
    assertEquals(perStep, run(100_000_000L));
    assertEquals(perStep, run(7 * SECOND));
    assertEquals(perStep, run(Long.MAX_VALUE / 4));
    assertTrue(perStep.size() > 18);
  }

  // ------------------------------------------------------------------ queue rules

  /**
   * A bid joined at the providers' best bid waits behind the size displayed there: the quantity
   * ahead only falls, the order is filled at its own price, and the fills add up to the order.
   */
  @Test
  void restingOrderKeepsItsPlaceBehindTheDisplayedSize() {
    int filledByQueue = 0;
    int filledByCross = 0;
    for (int s = 0; s < 12; s++) {
      final MarketModel m = session(200 + s);
      final BookModel book = m.book(note);
      book.advanceTo(TEN_AM);
      final double price = book.bestBid();
      final double displayed = book.displayedUnits(true, 0);
      final BookModel.Execution e = book.submit(TEN_AM, true, 5e6, price, false);
      assertEquals(0.0, e.filledUnits(), 0.0);
      assertTrue(Double.isNaN(e.averagePrice()));
      assertTrue(e.restingId() > 0);
      assertEquals(displayed, book.queueAhead(e.restingId()), 0.0);
      // no impact from an order that only rests
      assertEquals(0.0, m.levelOffset(TEN_AM), 0.0);
      assertEquals(0.0, book.ownDayVolume(), 0.0);

      double ahead = displayed;
      double filled = 0.0;
      for (long t = TEN_AM + SECOND; t <= TEN_AM + 1800 * SECOND && filled < 5e6; t += SECOND) {
        final double bidBefore = book.bestBid();
        book.advanceTo(t);
        for (final BookModel.PassiveFill f : book.drainPassiveFills()) {
          assertEquals(e.restingId(), f.orderId());
          assertEquals(price, f.price(), 0.0);
          assertTrue(f.buy());
          assertEquals(t, f.nanos());
          filled += f.quantityUnits();
          // either the queue ahead was exhausted by sellers, or the market came down through the bid
          if (book.bestAsk() <= price) {
            filledByCross++;
          } else {
            assertTrue(bidBefore >= price, "a fill from the queue needs the bid to have been at the price");
            filledByQueue++;
          }
        }
        final double now = book.queueAhead(e.restingId());
        if (!Double.isNaN(now)) {
          assertTrue(now <= ahead + 1e-6, "queue ahead rose from " + ahead + " to " + now);
          ahead = now;
        }
      }
      if (filled < 5e6) {
        assertTrue(book.cancel(e.restingId()));
        assertFalse(book.cancel(e.restingId()));
      }
      assertTrue(Double.isNaN(book.queueAhead(e.restingId())));
      assertTrue(filled <= 5e6 + 1e-6);
    }
    assertTrue(filledByQueue + filledByCross >= 6, "fills " + filledByQueue + " + " + filledByCross);
    assertTrue(filledByQueue > 0);
  }

  @Test
  void limitOrdersTradeUpToTheirLimitAndRestBehindIt() {
    final MarketModel m = session(4);
    final BookModel book = m.book(note);
    book.advanceTo(TEN_AM);
    final double ask = book.bestAsk();
    final double bid = book.bestBid();
    assertTrue(ask - bid == tick || ask - bid == 2 * tick);
    assertTrue(bid < book.displacedMid() && book.displacedMid() < ask);

    // immediate-or-cancel below the ask: nothing trades, nothing rests
    final BookModel.Execution none = book.submit(TEN_AM, true, 5e6, bid, true);
    assertEquals(0.0, none.filledUnits(), 0.0);
    assertEquals(0, none.restingId());

    // a limit at the ask for more than is displayed there takes the level and rests the remainder
    final double atAsk = book.displayedUnits(false, 0);
    final BookModel.Execution e = book.submit(TEN_AM, true, atAsk + 7e6, ask, false);
    assertEquals(atAsk, e.filledUnits(), 1e-6);
    assertEquals(ask, e.averagePrice(), 1e-9);
    assertTrue(e.restingId() > 0);
    assertEquals(atAsk, book.ownDayVolume(), 1e-6);
    // the desk's own marketable order does not trade with the desk's resting order
    final double bidNow = book.bestBid();
    final BookModel.Execution sell = book.submit(TEN_AM, false, 3e6, Double.NaN, true);
    assertEquals(3e6, sell.filledUnits(), 0.0);
    assertEquals(bidNow, sell.averagePrice(), 1e-9);
    assertTrue(book.drainPassiveFills().isEmpty());

    // a sell limit far above the market rests with nothing ahead and is cancelled
    final BookModel.Execution far = book.submit(TEN_AM, false, 2e6, ask + 40 * tick, false);
    assertEquals(0.0, book.queueAhead(far.restingId()), 0.0);
    assertTrue(book.cancel(far.restingId()));
    // a market order larger than the five levels is filled for what is displayed; the rest is dropped
    final BookModel.Execution sweep = book.submit(TEN_AM + SECOND, false, 5000e6, Double.NaN, false);
    assertTrue(sweep.filledUnits() < 300e6 && sweep.filledUnits() > 100e6);
    assertEquals(0, sweep.restingId());
    for (int l = 0; l < p.bookLevels(); l++) {
      assertTrue(book.displayedUnits(true, l) >= 0.0);
    }

    assertThrows(IllegalArgumentException.class, () -> book.submit(TEN_AM + SECOND, true, 0.0, Double.NaN, true));
    assertThrows(IllegalArgumentException.class, () -> book.advanceTo(TEN_AM));
  }

  /** The book follows the displaced mid, not the latent mid: after a buy the quotes sit above where they were. */
  @Test
  void providersQuoteAroundTheDisplacedMid() {
    final MarketModel m = session(5);
    final MarketModel quiet = session(5);
    final BookModel book = m.book(note);
    final BookModel twin = quiet.book(note);
    book.submit(TEN_AM, true, 200e6, Double.NaN, true);
    twin.advanceTo(TEN_AM);
    final double moved = impact(note, 200e6);
    assertTrue(moved > 2 * tick);
    assertEquals(moved, book.displacedMid() - twin.displacedMid(), 1e-12);
    assertTrue(book.bestBid() >= twin.bestBid() + 2 * tick);
    assertTrue(book.bestBid() < book.displacedMid() && book.displacedMid() < book.bestAsk());
    // the latent mid moved by the permanent share only; the quotes are centred on neither run's latent mid
    assertEquals(
        p.get(Scalar.IMPACT_PERMANENT_SHARE) * moved, m.latentMid(note, TEN_AM) - quiet.latentMid(note, TEN_AM), 1e-12);
    assertEquals(book.transientDisplacement(), book.displacedMid() - m.latentMid(note, TEN_AM), 1e-12);
    assertTrue(Math.abs(0.5 * (book.bestBid() + book.bestAsk()) - book.displacedMid()) <= 0.5 * tick);
    // the order swept four levels; those prices are still in the book and still consumed, while
    // the price that entered the five levels behind them starts at its target
    assertTrue(book.displayedUnits(false, 0) < book.targetUnits(0));
    assertEquals(book.targetUnits(4), book.displayedUnits(false, 4), 0.0);
  }
}
