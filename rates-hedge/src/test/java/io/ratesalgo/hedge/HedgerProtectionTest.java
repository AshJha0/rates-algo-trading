package io.ratesalgo.hedge;

import static io.ratesalgo.hedge.HedgeFixture.B10;
import static io.ratesalgo.hedge.HedgeFixture.M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.RecordingHedgeOrderSink;
import io.ratesalgo.messages.sbe.Side;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/** Review 2026-11-23, Q10 and Q13: stale mids, input health, stalled parents, small plans. */
class HedgerProtectionTest {

  private static final long SECOND = 1_000_000_000L;

  @Test
  void aStaleMidIsNotPricedFrom() {
    final HedgeFixture f = new HedgeFixture();
    final Hedger h = f.hedger(Hedger.config().triggerStdDev(1_000.0).maxMidAgeNanos(5 * SECOND));
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10));
    f.clock.advance(5 * SECOND + 1);
    assertEquals(0, h.onTimer(f.clock.epochNanos()), "every mid is older than the maximum age");
    assertTrue(h.staleMidRefusals() > 0);
    assertEquals(h.staleMidRefusals(), h.parentsRefused());
    assertEquals(0, f.sink.parents().size());
    assertEquals(0L, f.risk.pendingSell(HedgeFixture.TY) + f.risk.pendingBuy(HedgeFixture.TY), "nothing reserved");
    f.restamp();
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0, "fresh mids: the hedge goes out");
    final RecordingHedgeOrderSink.Parent p = f.sink.parents().get(0);
    assertEquals(f.clock.epochNanos(), p.sentNanos());
  }

  @Test
  void aMidAtExactlyTheMaximumAgeIsStillUsed() {
    final HedgeFixture f = new HedgeFixture();
    final Hedger h = f.hedger(Hedger.config().triggerStdDev(1_000.0).maxMidAgeNanos(5 * SECOND));
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10));
    f.clock.advance(5 * SECOND);
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0);
    assertEquals(0L, h.staleMidRefusals());
  }

  @Test
  void nothingIsSentWhileTheInputsAreUnhealthy() {
    final HedgeFixture f = new HedgeFixture();
    final Hedger h = f.hedger(Hedger.config().triggerStdDev(1_000.0));
    final boolean[] healthy = {false};
    final long[] asked = new long[1];
    h.inputGate(
        now -> {
          asked[0] = now;
          return healthy[0];
        });
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10));
    assertEquals(0, h.onTimer(f.clock.epochNanos()));
    assertEquals(f.clock.epochNanos(), asked[0], "asked at the injected clock's time");
    assertEquals(1L, h.unhealthyTimers());
    assertEquals(0, f.sink.parents().size());
    assertTrue(h.residualVariance() > 0.0, "the residual is still measured");
    healthy[0] = true;
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0);
    h.inputGate(null);
    assertEquals(1L, h.unhealthyTimers());
  }

  @Test
  void aStalledParentStopsCountingAsAHedge() {
    final HedgeFixture f = new HedgeFixture();
    final Hedger h =
        f.hedger(Hedger.config().triggerStdDev(1_000.0).firstTranche(1.0).parentTimeoutNanos(60 * SECOND));
    final long[] told = new long[4];
    h.stallListener(
        (id, instrument, remaining, age) -> {
          told[0]++;
          told[1] = id;
          told[2] = remaining;
          told[3] = age;
        });
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10)); // -82k at 10Y
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0);
    final int working = h.workingParents();
    final double hedged = h.residualVariance();
    f.clock.advance(60 * SECOND);
    f.restamp();
    h.onTimer(f.clock.epochNanos());
    assertEquals(0L, h.stalledParents(), "at the timeout, not past it");

    // the venue never answers: one nanosecond later the parents are cancelled and the risk is open
    f.clock.advance(1);
    final Dv01Vector open = new Dv01Vector(CurrencyCode.USD);
    f.risk.ladder(CurrencyCode.USD, open);
    final int sentBefore = f.sink.parents().size();
    final int sent = h.onTimer(f.clock.epochNanos());
    assertEquals(working, h.stalledParents());
    assertEquals(working, h.stalledWorking());
    assertEquals(working, f.sink.cancelled().size(), "each stalled parent is cancelled");
    assertEquals(working, told[0]);
    assertEquals(60 * SECOND + 1, told[3]);
    assertTrue(told[2] != 0);
    assertTrue(sent > 0, "the open risk is hedged again");
    assertEquals(sentBefore + sent, f.sink.parents().size());
    assertTrue(h.residualVariance() <= hedged * 1.01, "residual with the replacement is the hedged one again");

    // the stalled parent still holds its reservation until the router lets go of it
    final RecordingHedgeOrderSink.Parent stalled = f.sink.parents().get(0);
    final long reservedBefore = reserved(f, stalled);
    assertTrue(reservedBefore >= stalled.quantityMinor());
    // a fill that still arrives is booked
    final long position = f.risk.position(stalled.instrumentId());
    h.onHedgeFill(stalled.parentId(), stalled.limitPrice(), f.directory.byId(stalled.instrumentId()).minorPerMajor());
    assertTrue(f.risk.position(stalled.instrumentId()) != position);
    h.onParentDone(stalled.parentId());
    assertEquals(reservedBefore - stalled.quantityMinor(), reserved(f, stalled));
    assertEquals(working - 1, h.stalledWorking());
    h.stallListener(null);
  }

  @Test
  void theResidualIgnoresAStalledParent() {
    final HedgeFixture f = new HedgeFixture();
    // a trigger so high after the first tranche that nothing more is sent: only the measure moves
    final Hedger h =
        f.hedger(Hedger.config().triggerStdDev(1_000.0).firstTranche(1.0).parentTimeoutNanos(10 * SECOND));
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10));
    h.onTimer(f.clock.epochNanos());
    f.sink.accepting(false); // the router is gone: no replacement can hide the gap
    h.onTimer(f.clock.epochNanos());
    final double hedged = h.residualVariance();
    f.clock.advance(10 * SECOND + 1);
    f.restamp();
    h.onTimer(f.clock.epochNanos());
    final Dv01Vector ladder = new Dv01Vector(CurrencyCode.USD);
    final Dv01Vector residual = new Dv01Vector(CurrencyCode.USD);
    f.risk.ladder(CurrencyCode.USD, ladder);
    h.residual(residual);
    assertEquals(ladder.parallel(), residual.parallel(), 1e-6, "the residual is the unhedged ladder");
    assertTrue(h.residualVariance() > 10.0 * hedged);
  }

  @Test
  void aRouterThatReportsDoneInsideTheCancelIsSafe() {
    final HedgeFixture f = new HedgeFixture();
    final Hedger[] holder = new Hedger[1];
    final java.util.List<Long> ids = new java.util.ArrayList<>();
    final io.ratesalgo.api.HedgeOrderSink sink =
        new io.ratesalgo.api.HedgeOrderSink() {
          @Override
          public boolean submit(
              final long parentId, final int instrumentId, final Side side, final long quantityMinor,
              final long limitPrice, final long urgencyNanos, final long sentNanos) {
            ids.add(parentId);
            return true;
          }

          @Override
          public void cancel(final long parentId) {
            holder[0].onParentDone(parentId);
          }

          @Override
          public void cancelAll() {}
        };
    final Hedger h =
        new Hedger(
            CurrencyCode.USD, f.planner(1e-5), f.risk, sink, f.fair, new HedgeAnalytics(64, BasisAttribution.NONE),
            f.clock, Hedger.config().triggerStdDev(1_000.0).firstTranche(1.0).parentTimeoutNanos(SECOND));
    holder[0] = h;
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10));
    h.onTimer(f.clock.epochNanos());
    final int first = ids.size();
    assertTrue(first > 0);
    f.clock.advance(SECOND + 1);
    f.restamp();
    h.onTimer(f.clock.epochNanos());
    assertEquals(first, h.stalledParents());
    assertEquals(0, h.stalledWorking(), "done inside the cancel: gone, reservation released");
    assertEquals(ids.size() - first, h.workingParents());
  }

  /** Q13: with the carry, a small plan follows the half-life instead of a lot per timer. */
  @Test
  void aSmallPlanFollowsTheHalfLifeWhenFractionsAreCarried() {
    final long halfLife = 5 * SECOND;
    final long step = SECOND / 4;
    final double[] sentAtHalfLife = new double[2];
    for (int mode = 0; mode < 2; mode++) {
      final HedgeFixture f = new HedgeFixture();
      final Hedger h =
          f.hedger(
              Hedger.config().triggerStdDev(1.0).firstTranche(0.5).halfLifeNanos(halfLife).carryFractionalLots(mode == 1));
      f.risk.onFill(B10, 2 * M, f.fair.fairMid(B10)); // -1.64k: a plan of about twenty TY lots
      h.onTimer(f.clock.epochNanos());
      fill(f, h);
      final double afterFirst = Math.abs(f.risk.netPv01(CurrencyCode.USD));
      for (long t = step; t <= halfLife; t += step) {
        f.clock.advance(step);
        f.restamp();
        h.onTimer(f.clock.epochNanos());
        fill(f, h);
      }
      sentAtHalfLife[mode] = 1.0 - Math.abs(f.risk.netPv01(CurrencyCode.USD)) / afterFirst;
    }
    assertTrue(sentAtHalfLife[0] > 0.7, "a lot per timer runs well ahead of the schedule: " + sentAtHalfLife[0]);
    // half of what was left, less the part-lots still carried on each instrument
    assertTrue(sentAtHalfLife[1] > 0.3 && sentAtHalfLife[1] <= 0.55, "carried: " + sentAtHalfLife[1]);
  }

  /**
   * Whatever the timing, the parents a hedger has working and not stalled, plus the ladder, are
   * its residual; a stalled parent is never in it and never loses its reservation early.
   */
  @Property(tries = 60)
  void stalledParentsAreNeverCountedAndNeverUnreserved(
      @ForAll @IntRange(min = 1, max = 12) final int steps,
      @ForAll @LongRange(min = 1, max = 40) final long stepSeconds,
      @ForAll @LongRange(min = 5, max = 60) final long timeoutSeconds,
      @ForAll @IntRange(min = 10, max = 200) final int millions) {
    final HedgeFixture f = new HedgeFixture();
    final Hedger h =
        f.hedger(Hedger.config().triggerStdDev(1_000.0).parentTimeoutNanos(timeoutSeconds * SECOND));
    f.risk.onFill(B10, millions * M, f.fair.fairMid(B10));
    final Dv01Vector ladder = new Dv01Vector(CurrencyCode.USD);
    final Dv01Vector residual = new Dv01Vector(CurrencyCode.USD);
    for (int s = 0; s < steps; s++) {
      // the residual is measured before this timer's parents go out
      final int measured = f.sink.parents().size();
      h.onTimer(f.clock.epochNanos());
      f.risk.ladder(CurrencyCode.USD, ladder);
      h.residual(residual);
      double live = 0.0;
      long buys = 0L;
      long sells = 0L;
      for (int n = 0; n < f.sink.parents().size(); n++) {
        final RecordingHedgeOrderSink.Parent p = f.sink.parents().get(n);
        final boolean stalled = f.clock.epochNanos() - p.sentNanos() > timeoutSeconds * SECOND;
        assertEquals(stalled, f.sink.cancelled().contains(p.parentId()), "cancelled exactly when past the timeout");
        final long signed = p.side() == Side.Buy ? p.quantityMinor() : -p.quantityMinor();
        if (!stalled && n < measured) {
          live += f.risk.unitParallelPv01(p.instrumentId()) * f.directory.byId(p.instrumentId()).major(signed);
        }
        buys += Math.max(signed, 0L);
        sells += Math.max(-signed, 0L);
      }
      assertEquals(ladder.parallel() + live, residual.parallel(), 1e-6 * (1.0 + Math.abs(ladder.parallel())));
      long reservedBuy = 0L;
      long reservedSell = 0L;
      for (int j = 0; j < f.universe.size(); j++) {
        reservedBuy += f.risk.pendingBuy(f.universe.instrumentId(j));
        reservedSell += f.risk.pendingSell(f.universe.instrumentId(j));
      }
      assertEquals(buys, reservedBuy, "every parent, stalled or not, is still reserved");
      assertEquals(sells, reservedSell);
      f.clock.advance(stepSeconds * SECOND);
      f.restamp();
    }
  }

  @Test
  void configValidation() {
    assertThrows(IllegalArgumentException.class, () -> Hedger.config().maxMidAgeNanos(0));
    assertThrows(IllegalArgumentException.class, () -> Hedger.config().parentTimeoutNanos(0));
    assertFalse(Hedger.config().carryFractionalLots);
    assertTrue(HedgeInputGate.ALWAYS.healthy(0L));
  }

  private static long reserved(final HedgeFixture f, final RecordingHedgeOrderSink.Parent p) {
    return p.side() == Side.Buy ? f.risk.pendingBuy(p.instrumentId()) : f.risk.pendingSell(p.instrumentId());
  }

  private static void fill(final HedgeFixture f, final Hedger h) {
    for (int p = h.workingParents() - 1; p >= 0; p--) {
      final long id = h.workingParentId(p);
      for (final RecordingHedgeOrderSink.Parent par : f.sink.parents()) {
        if (par.parentId() == id) {
          h.onHedgeFill(id, f.fair.fairMid(par.instrumentId()), Math.abs(h.remaining(id)));
        }
      }
    }
  }
}
