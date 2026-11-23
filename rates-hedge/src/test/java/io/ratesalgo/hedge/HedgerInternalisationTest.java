package io.ratesalgo.hedge;

import static io.ratesalgo.hedge.HedgeFixture.B10;
import static io.ratesalgo.hedge.HedgeFixture.M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.InMemoryBookSource;
import io.ratesalgo.api.RecordingHedgeOrderSink;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.Side;
import org.junit.jupiter.api.Test;

/**
 * Review 2026-11-23, design change 5: hold inside a variance budget with a time stop, rest at the
 * touch first, cross on a breach or at the time stop; with the stale-mid, input-health and stalled
 * parent rules of DR-RV-Q01 still in force.
 */
class HedgerInternalisationTest {

  private static final long SECOND = 1_000_000_000L;
  private static final long STOP = 30 * SECOND;

  private final HedgeFixture f = new HedgeFixture();
  private final InMemoryBookSource book = new InMemoryBookSource(16);

  private Hedger hedger(final boolean passive) {
    final Hedger h =
        f.hedger(Hedger.config().triggerStdDev(1_000.0).firstTranche(1.0).internalise(20_000.0, STOP).passiveFirst(passive));
    h.touch(book);
    return h;
  }

  /** A book one 1/128 tick wide around every hedge instrument's mid, stamped now. */
  private void quoteBooks() {
    final long now = f.clock.epochNanos();
    for (int j = 0; j < f.universe.size(); j++) {
      final int id = f.universe.instrumentId(j);
      final long mid = f.fair.fairMid(id);
      book.set(id, mid - 3_906_250L, 50 * M, mid + 3_906_250L, 50 * M, now);
    }
  }

  private void advance(final long nanos) {
    f.clock.advance(nanos);
    f.restamp();
    quoteBooks();
  }

  @Test
  void insideTheBudgetTheResidualIsHeldUntilTheTimeStop() {
    final Hedger h = hedger(false);
    f.risk.onFill(B10, 2 * M, f.fair.fairMid(B10));
    assertEquals(0, h.onTimer(f.clock.epochNanos()));
    assertTrue(Math.sqrt(h.residualVariance()) > 1_000.0, "above the trigger");
    assertEquals(1L, h.internalisedTimers());
    advance(STOP - 1);
    assertEquals(0, h.onTimer(f.clock.epochNanos()));
    assertEquals(0, f.sink.parents().size());
    advance(1);
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0, "the time stop crosses");
    assertEquals(1L, h.timeStops());
    assertEquals(0L, h.budgetBreaches());
    assertTrue(h.inEpisode());
  }

  @Test
  void aBreachOfTheBudgetCrossesAtOnce() {
    final Hedger h = hedger(true);
    quoteBooks();
    f.risk.onFill(B10, 20 * M, f.fair.fairMid(B10));
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0);
    assertEquals(1L, h.budgetBreaches());
    assertEquals(0L, h.passiveSent(), "no resting order when the budget is breached");
    // still in the episode on the next timer: the count is of episodes, not timers
    h.onTimer(f.clock.epochNanos());
    assertEquals(1L, h.budgetBreaches());
  }

  @Test
  void whileHoldingTheHedgeRestsAtOurSideOfTheTouchAndDoesNotCount() {
    final Hedger h = hedger(true);
    quoteBooks();
    f.risk.onFill(B10, 2 * M, f.fair.fairMid(B10));
    final int resting = h.onTimer(f.clock.epochNanos());
    assertTrue(resting > 0);
    assertEquals(resting, h.livePassive());
    assertEquals(resting, (int) h.passiveSent());
    for (final RecordingHedgeOrderSink.Parent p : f.sink.parents()) {
      // our own side of the book: a sell rests at the offer, a buy at the bid; neither can cross
      assertEquals(p.side() == Side.Sell ? book.bestAsk(p.instrumentId()) : book.bestBid(p.instrumentId()), p.limitPrice());
      assertEquals(f.clock.epochNanos() + STOP, p.urgencyNanos());
    }
    final Dv01Vector working = new Dv01Vector(CurrencyCode.USD);
    h.addWorking(working);
    assertEquals(0.0, working.parallel(), "the quoting engine does not count a resting order");
    advance(SECOND);
    assertEquals(0, h.onTimer(f.clock.epochNanos()), "one wave at a time");
    assertEquals(ladderVariance(), h.residualVariance(), 1e-6 * ladderVariance(), "a resting order is not a hedge");
    // at the time stop the resting orders are withdrawn and the hedger crosses
    advance(STOP);
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0);
    assertEquals(resting, (int) h.passiveWithdrawn());
    assertEquals(0, h.livePassive());
    final RecordingHedgeOrderSink.Parent first = f.sink.parents().get(0);
    assertTrue(f.sink.cancelled().contains(first.parentId()));
    final RecordingHedgeOrderSink.Parent cross = f.sink.parents().get(resting);
    final double mid = Decimal9.toDouble(f.fair.fairMid(cross.instrumentId()));
    final double offset = 2.0 * f.risk.quotePerBp(cross.instrumentId());
    assertEquals(cross.side() == Side.Sell ? Decimal9.floor(mid - offset) : Decimal9.ceil(mid + offset), cross.limitPrice());
    h.addWorking(working);
    assertTrue(working.parallel() != 0.0, "a crossing parent counts");
    // a withdrawn order stays reserved until the router reports it done, and a late fill books
    final long before = f.risk.position(first.instrumentId());
    h.onHedgeFill(first.parentId(), first.limitPrice(), first.quantityMinor() / 2);
    assertTrue(f.risk.position(first.instrumentId()) != before);
    h.onParentDone(first.parentId());
    assertEquals(f.sink.parents().size() - 1, h.workingParents());
  }

  @Test
  void aRestingOrderIsWithdrawnOnceTheResidualIsBackUnderTheTrigger() {
    final Hedger h = hedger(true);
    quoteBooks();
    f.risk.onFill(B10, 2 * M, f.fair.fairMid(B10));
    h.onTimer(f.clock.epochNanos());
    final long id = f.sink.parents().get(0).parentId();
    // a client takes the risk back: the resting hedge would now add risk
    f.risk.onFill(B10, -2 * M, f.fair.fairMid(B10));
    advance(SECOND);
    assertEquals(0, h.onTimer(f.clock.epochNanos()));
    assertTrue(f.sink.cancelled().contains(id));
    assertEquals(1L, h.passiveWithdrawn());
    assertFalse(h.inEpisode());
  }

  @Test
  void withoutAFreshTouchNothingRestsButTheTimeStopStillCrosses() {
    final Hedger h = hedger(true);
    f.risk.onFill(B10, 2 * M, f.fair.fairMid(B10));
    h.touch(null);
    assertEquals(0, h.onTimer(f.clock.epochNanos()));
    assertTrue(h.passiveRefusals() > 0);
    h.touch(book);
    quoteBooks();
    f.clock.advance(6 * SECOND);
    f.restamp();
    assertEquals(0, h.onTimer(f.clock.epochNanos()), "the touch is older than the maximum mid age");
    advance(STOP);
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0);
    assertEquals(1L, h.timeStops());
  }

  @Test
  void unhealthyInputsSendNothingAndTheTimeStopClockKeepsRunning() {
    final Hedger h = hedger(false);
    final boolean[] healthy = {false};
    h.inputGate(now -> healthy[0]);
    f.risk.onFill(B10, 2 * M, f.fair.fairMid(B10));
    assertEquals(0, h.onTimer(f.clock.epochNanos()));
    assertEquals(1L, h.unhealthyTimers());
    advance(STOP);
    assertEquals(0, h.onTimer(f.clock.epochNanos()));
    healthy[0] = true;
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0, "recovered past the time stop: crosses at once");
  }

  @Test
  void aKillEndsTheHold() {
    final Hedger h = hedger(true);
    quoteBooks();
    f.risk.onFill(B10, 2 * M, f.fair.fairMid(B10));
    h.onTimer(f.clock.epochNanos());
    f.kill.trigger(KillReason.Manual, 0, 1);
    assertEquals(0, h.workingParents());
    assertEquals(0, h.onTimer(f.clock.epochNanos()));
  }

  @Test
  void theBudgetIsValidated() {
    assertThrows(IllegalArgumentException.class, () -> Hedger.config().internalise(0.0, STOP));
    assertThrows(IllegalArgumentException.class, () -> Hedger.config().internalise(Double.POSITIVE_INFINITY, STOP));
    assertThrows(IllegalArgumentException.class, () -> Hedger.config().internalise(1.0, 0L));
    assertThrows(IllegalArgumentException.class, () -> f.hedger(Hedger.config().triggerStdDev(5_000.0).internalise(1_000.0, STOP)));
    assertFalse(Hedger.config().internalising());
    assertTrue(Hedger.config().internalise(1.0, 1L).internalising());
  }

  private double ladderVariance() {
    final Dv01Vector r = new Dv01Vector(CurrencyCode.USD);
    f.risk.ladder(CurrencyCode.USD, r);
    final double[] a = new double[8];
    r.copyTo(a);
    return KeyRateCovariance.assumedDefault().quadratic(a, a);
  }
}
